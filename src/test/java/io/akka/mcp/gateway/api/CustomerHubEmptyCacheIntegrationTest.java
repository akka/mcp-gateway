package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.CustomerHubConnectionEntity;
import io.akka.mcp.gateway.application.CustomerHubMcpClient;
import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.testsupport.FakeMcpServer;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.AdvertisedTool;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import io.akka.mcp.gateway.testsupport.LogCapture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A user who is already connected while nothing is cached yet, for example after the cache was lost, is the one case
 * where {@code tools/list} asks the hub. The tests run in order because the first successful listing fills the cache
 * the failing ones must find empty. Users are connected through the entity, so the connect route never fills the cache.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CustomerHubEmptyCacheIntegrationTest extends TestKitSupport {

    private static final String APP_ID = CustomerHubIntegrationTest.APP_ID;
    private static final String GET_REQUEST = "CustomerHub_get_request";

    private static final FakeMcpServer HUB = FakeMcpServer.start().advertising(new AdvertisedTool("get_request", true));

    @AfterAll
    public static void stopHub() {
        HUB.close();
    }

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                customer-hub.mcp-url = "%s"
                customer-hub.okta-app-id = "%s"
                """.formatted(HUB.url(), APP_ID));
    }

    private record Connected(String mcpToken, String browserSession) {}

    private Connected connectedUser(String hubToken) {
        var email = "empty-cache-" + UUID.randomUUID() + "@lightbend.com";
        var apps = List.of(new UserSession.App(APP_ID, CustomerHubMcpClient.MCP_NAME));
        componentClient.forKeyValueEntity(email).method(CustomerHubConnectionEntity::connect).invoke(hubToken);
        return new Connected(
                GatewayFixtures.mcpToken(componentClient, email, List.of(CustomerHubIntegrationTest.READER_GROUP), apps),
                GatewayFixtures.browserSession(componentClient, email, List.of(), apps));
    }

    private boolean stillConnected(Connected user) throws Exception {
        var status = httpClient.GET("/customer-hub/status").addHeader("Cookie", "SESSION=" + user.browserSession()).invoke();
        return JsonSupport.getObjectMapper().readTree(status.body().utf8String()).path("connected").asBoolean();
    }

    private List<String> listedTools(String mcpToken) throws Exception {
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + mcpToken)
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .responseBodyAs(String.class)
                .invoke();
        var names = new ArrayList<String>();
        JsonSupport.getObjectMapper().readTree(response.body()).path("result").path("tools")
                .forEach(tool -> names.add(tool.path("name").asText()));
        return names;
    }

    @Test
    @Order(1)
    public void toolsList_whenTheHubIsDown_listsNoHubToolsYet_andLeavesTheUserConnected() throws Throwable {
        HUB.respondingWithStatus(503);
        var user = connectedUser("hubtoken-" + UUID.randomUUID());

        var logged = LogCapture.during(() -> assertThat(listedTools(user.mcpToken())).doesNotContain(GET_REQUEST).contains("howto_get_started"));

        assertThat(logged).noneMatch(line -> line.contains(FakeMcpServer.FAILURE_BODY));
        assertThat(stillConnected(user)).isTrue();
        HUB.respondingNormally();
    }

    @Test
    @Order(2)
    public void toolsList_whenTheHubRefusesTheToken_listsNoHubToolsYet_andLeavesTheUserConnected() throws Throwable {
        HUB.refusingEveryToken();
        var user = connectedUser("hubtoken-" + UUID.randomUUID());

        var logged = LogCapture.during(() -> assertThat(listedTools(user.mcpToken())).doesNotContain(GET_REQUEST).contains("howto_get_started"));

        assertThat(logged).noneMatch(line -> line.contains(FakeMcpServer.FAILURE_BODY));
        assertThat(stillConnected(user)).isTrue();
        HUB.respondingNormally();
        HUB.acceptingOnlyBearer();
    }

    @Test
    @Order(3)
    public void toolsList_whenTheHubAnswers_listsItsTools_andFillsTheCacheForEveryone() throws Exception {
        var token = "hubtoken-" + UUID.randomUUID();
        HUB.acceptingOnlyBearer(token);
        var connected = connectedUser(token).mcpToken();
        var bystander = GatewayFixtures.mcpToken(componentClient, "bystander-" + UUID.randomUUID() + "@lightbend.com", List.of(),
                List.of(new UserSession.App(APP_ID, "Customer Hub")));
        var before = HUB.requestsReceived();

        var tools = listedTools(connected);

        assertThat(tools).contains(GET_REQUEST);
        assertThat(HUB.requestsReceived()).isGreaterThan(before);
        assertThat(listedTools(bystander)).contains(GET_REQUEST);
    }
}
