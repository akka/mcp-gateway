package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.mcp.gateway.application.TokenMcpConfig;
import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.testsupport.FakeMcpServer;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.AdvertisedTool;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.ReceivedCall;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Two connectors set up side by side keep a user's tokens apart and route each call to its own upstream. */
public class TokenMcpTwoConnectorsIntegrationTest extends TestKitSupport {

    private static final String GATEWAY_ORIGIN = "http://localhost:9000";
    private static final String ALPHA_APP = "alpha-app-under-test";
    private static final String BETA_APP = "beta-app-under-test";
    private static final FakeMcpServer ALPHA = FakeMcpServer.start().advertising(new AdvertisedTool("get_item", true));
    private static final FakeMcpServer BETA = FakeMcpServer.start().advertising(new AdvertisedTool("get_item", true));

    @AfterAll
    public static void stopUpstreams() {
        ALPHA.close();
        BETA.close();
    }

    private static String entry(String id, String name, String prefix, FakeMcpServer upstream, String appId) {
        return """
                {"id":"%s","name":"%s","tool-prefix":"%s","mcp-url":"%s","okta-app-id":"%s"}"""
                .formatted(id, name, prefix, upstream.url(), appId);
    }

    private static String entryWithHint(String id, String name, String prefix, FakeMcpServer upstream, String appId, String hint) {
        var plain = entry(id, name, prefix, upstream, appId);
        return plain.substring(0, plain.length() - 1) + ",\"connect-hint\":\"" + hint + "\"}";
    }

    @Override
    protected TestKit.Settings testKitSettings() {
        var entries = "[" + entry("beta-service", "Beta Service", "Beta_", BETA, BETA_APP)
                + "," + entryWithHint("alpha-service", "Alpha Service", "Alpha_", ALPHA, ALPHA_APP, "Create a key in Alpha.") + "]";
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                token-mcps = \"\"\"%s\"\"\"
                mcp.base-url = "%s"
                okta.groups.reader = "%s"
                """.formatted(entries, GATEWAY_ORIGIN, TokenMcpIntegrationTest.READER_GROUP));
    }

    @BeforeEach
    public void freshUpstreamsAndReadOnlyPolicy() {
        for (var upstream : List.of(ALPHA, BETA)) {
            upstream.forgetCalls();
            upstream.respondingNormally();
            upstream.refusingEveryToken();
        }
        GatewayFixtures.enableWritesOn(componentClient, List.of());
    }

    private record SignedIn(String email, String browserSession, String mcpToken) {}

    private SignedIn signIn() {
        return signIn(List.of(new UserSession.App(ALPHA_APP, "Alpha Service"), new UserSession.App(BETA_APP, "Beta Service")));
    }

    private SignedIn signIn(List<UserSession.App> apps) {
        var email = "two-connectors-" + UUID.randomUUID() + "@lightbend.com";
        return new SignedIn(email, GatewayFixtures.browserSession(componentClient, email, List.of(), apps),
                GatewayFixtures.mcpToken(componentClient, email, List.of(TokenMcpIntegrationTest.READER_GROUP), apps));
    }

    private int connect(SignedIn user, String connectorId, String token) {
        return httpClient.POST("/token-mcp/" + connectorId + "/connect")
                .addHeader("Cookie", "SESSION=" + user.browserSession())
                .addHeader("Origin", GATEWAY_ORIGIN)
                .withRequestBody(new TokenMcpEndpoint.ConnectRequest(token))
                .invoke().status().intValue();
    }

    private int disconnect(SignedIn user, String connectorId) {
        return httpClient.POST("/token-mcp/" + connectorId + "/disconnect")
                .addHeader("Cookie", "SESSION=" + user.browserSession())
                .addHeader("Origin", GATEWAY_ORIGIN)
                .invoke().status().intValue();
    }

    private boolean connected(SignedIn user, String connectorId) throws Exception {
        var body = httpClient.GET("/token-mcp/" + connectorId + "/status").addHeader("Cookie", "SESSION=" + user.browserSession())
                .invoke().body().utf8String();
        return JsonSupport.getObjectMapper().readTree(body).path("connected").asBoolean();
    }

    private JsonNode callTool(SignedIn user, String toolName) throws Exception {
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + user.mcpToken())
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", Map.of("name", toolName, "arguments", Map.of())))
                .responseBodyAs(String.class)
                .invoke();
        return JsonSupport.getObjectMapper().readTree(response.body());
    }

    private List<String> listedTools(SignedIn user) throws Exception {
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + user.mcpToken())
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .responseBodyAs(String.class)
                .invoke();
        var names = new ArrayList<String>();
        JsonSupport.getObjectMapper().readTree(response.body()).path("result").path("tools")
                .forEach(tool -> names.add(tool.path("name").asText()));
        return names;
    }

    private static boolean isToolError(JsonNode response) {
        return response.path("result").path("isError").asBoolean(false);
    }

    @Test
    public void eachConnectorKeepsItsOwnTokenForTheSameUser_andForwardsItToItsOwnUpstream() throws Exception {
        var alphaToken = "alphatoken-" + UUID.randomUUID();
        var betaToken = "betatoken-" + UUID.randomUUID();
        ALPHA.acceptingOnlyBearer(alphaToken);
        BETA.acceptingOnlyBearer(betaToken);
        var user = signIn();

        assertThat(connect(user, "alpha-service", alphaToken)).isEqualTo(200);
        assertThat(connect(user, "beta-service", betaToken)).isEqualTo(200);
        ALPHA.forgetCalls();
        BETA.forgetCalls();
        var alpha = callTool(user, "Alpha_get_item");
        var beta = callTool(user, "Beta_get_item");

        assertThat(isToolError(alpha)).isFalse();
        assertThat(isToolError(beta)).isFalse();
        assertThat(ALPHA.callsTo("get_item")).extracting(ReceivedCall::authorization).containsExactly("Bearer " + alphaToken);
        assertThat(BETA.callsTo("get_item")).extracting(ReceivedCall::authorization).containsExactly("Bearer " + betaToken);
    }

    @Test
    public void aTokenOneConnectorAccepts_isNotKeptForTheOther() throws Exception {
        var alphaToken = "alphatoken-" + UUID.randomUUID();
        ALPHA.acceptingOnlyBearer(alphaToken);
        BETA.acceptingOnlyBearer("betatoken-" + UUID.randomUUID());
        var user = signIn();
        assertThat(connect(user, "alpha-service", alphaToken)).isEqualTo(200);

        assertThat(connect(user, "beta-service", alphaToken)).isEqualTo(400);

        assertThat(connected(user, "alpha-service")).isTrue();
        assertThat(connected(user, "beta-service")).isFalse();
    }

    @Test
    public void disconnectingOneConnector_leavesTheOtherConnected() throws Exception {
        var alphaToken = "alphatoken-" + UUID.randomUUID();
        var betaToken = "betatoken-" + UUID.randomUUID();
        ALPHA.acceptingOnlyBearer(alphaToken);
        BETA.acceptingOnlyBearer(betaToken);
        var user = signIn();
        assertThat(connect(user, "alpha-service", alphaToken)).isEqualTo(200);
        assertThat(connect(user, "beta-service", betaToken)).isEqualTo(200);

        assertThat(disconnect(user, "alpha-service")).isEqualTo(200);

        assertThat(connected(user, "alpha-service")).isFalse();
        assertThat(connected(user, "beta-service")).isTrue();
    }

    @Test
    public void bothConnectorsToolsAreListed_andTheirHowToToolsAreOffered_onceTheyAreSetUp() throws Exception {
        var alphaToken = "alphatoken-" + UUID.randomUUID();
        var betaToken = "betatoken-" + UUID.randomUUID();
        ALPHA.acceptingOnlyBearer(alphaToken);
        BETA.acceptingOnlyBearer(betaToken);
        var user = signIn();
        assertThat(connect(user, "alpha-service", alphaToken)).isEqualTo(200);
        assertThat(connect(user, "beta-service", betaToken)).isEqualTo(200);

        assertThat(listedTools(user)).contains("Alpha_get_item", "Beta_get_item", "howto_connect_alpha_service", "howto_connect_beta_service");
    }

    @Test
    public void status_forAUserWhoLostTheConnectorsApp_isNotConnected() throws Exception {
        var token = "alphatoken-" + UUID.randomUUID();
        ALPHA.acceptingOnlyBearer(token);
        var user = signIn();
        assertThat(connect(user, "alpha-service", token)).isEqualTo(200);
        assertThat(connected(user, "alpha-service")).isTrue();

        var withoutTheApp = new SignedIn(user.email(),
                GatewayFixtures.browserSession(componentClient, user.email(), List.of(), List.of()), user.mcpToken());

        assertThat(connected(withoutTheApp, "alpha-service")).isFalse();
    }

    @Test
    public void theConnectorList_describesEachConnectorInNameOrder() throws Exception {
        var list = connectors(signIn());

        assertThat(list).hasSize(2);
        assertThat(list.get(0).path("id").asText()).isEqualTo("alpha-service");
        assertThat(list.get(0).path("name").asText()).isEqualTo("Alpha Service");
        assertThat(list.get(0).path("connectHint").asText()).isEqualTo("Create a key in Alpha.");
        assertThat(list.get(1).path("id").asText()).isEqualTo("beta-service");
        assertThat(list.get(1).path("connectHint").asText()).isEqualTo(TokenMcpConfig.DEFAULT_CONNECT_HINT);
        assertThat(list.toString()).doesNotContain(ALPHA.url()).doesNotContain(BETA.url()).doesNotContain(ALPHA_APP);
    }

    private JsonNode connectors(SignedIn user) throws Exception {
        var response = httpClient.GET("/token-mcp/connectors").addHeader("Cookie", "SESSION=" + user.browserSession()).invoke();
        assertThat(response.status().intValue()).isEqualTo(200);
        return JsonSupport.getObjectMapper().readTree(response.body().utf8String());
    }

    @Test
    public void theConnectorList_needsASession() {
        assertThat(httpClient.GET("/token-mcp/connectors").invoke().status().intValue()).isEqualTo(401);
    }
}
