package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.TokenConnectionEntity;
import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.testsupport.FakeMcpServer;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.AdvertisedTool;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import io.akka.mcp.gateway.testsupport.LogCapture;
import io.akka.mcp.gateway.testsupport.TokenMcpFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * where {@code tools/list} asks the upstream. The tests run in order because the first successful listing fills the cache
 * the failing ones must find empty. Users are connected through the entity, so the connect route never fills the cache.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TokenMcpEmptyCacheIntegrationTest extends TestKitSupport {

    private static final String APP_ID = TokenMcpIntegrationTest.APP_ID;
    private static final String GET_ITEM = "TestToken_get_item";

    private static final FakeMcpServer UPSTREAM = FakeMcpServer.start().advertising(new AdvertisedTool("get_item", true));

    @AfterEach
    public void upstreamAnswersNormallyAgain() {
        UPSTREAM.respondingNormally();
    }

    @AfterAll
    public static void stopUpstream() {
        UPSTREAM.close();
    }

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig(TokenMcpFixtures.setting(UPSTREAM.url(), APP_ID));
    }

    private record Connected(String mcpToken, String browserSession) {}

    private Connected connectedUser(String upstreamToken) {
        var email = "empty-cache-" + UUID.randomUUID() + "@lightbend.com";
        var apps = List.of(new UserSession.App(APP_ID, TokenMcpFixtures.NAME));
        componentClient.forKeyValueEntity(TokenConnectionEntity.keyFor(TokenMcpFixtures.ID, email))
                .method(TokenConnectionEntity::connect).invoke(upstreamToken);
        return new Connected(
                GatewayFixtures.mcpToken(componentClient, email, List.of(TokenMcpIntegrationTest.READER_GROUP), apps),
                GatewayFixtures.browserSession(componentClient, email, List.of(), apps));
    }

    private boolean stillConnected(Connected user) throws Exception {
        var status = httpClient.GET(TokenMcpFixtures.path("status")).addHeader("Cookie", "SESSION=" + user.browserSession()).invoke();
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
    public void toolsList_whenTheUpstreamIsDown_listsNoUpstreamToolsYet_andLeavesTheUserConnected() throws Throwable {
        UPSTREAM.respondingWithStatus(503);
        var user = connectedUser("upstreamtoken-" + UUID.randomUUID());

        var logged = LogCapture.during(() -> assertThat(listedTools(user.mcpToken())).doesNotContain(GET_ITEM).contains("howto_get_started"));

        assertThat(logged).noneMatch(line -> line.contains(FakeMcpServer.FAILURE_BODY));
        assertThat(stillConnected(user)).isTrue();
    }

    @Test
    @Order(2)
    public void toolsList_whenTheUpstreamRefusesTheToken_listsNoUpstreamToolsYet_andLeavesTheUserConnected() throws Throwable {
        UPSTREAM.refusingEveryToken();
        var user = connectedUser("upstreamtoken-" + UUID.randomUUID());

        var logged = LogCapture.during(() -> assertThat(listedTools(user.mcpToken())).doesNotContain(GET_ITEM).contains("howto_get_started"));

        assertThat(logged).noneMatch(line -> line.contains(FakeMcpServer.FAILURE_BODY));
        assertThat(stillConnected(user)).isTrue();
    }

    @Test
    @Order(3)
    public void toolsList_whenTheUpstreamAnswers_listsItsTools_andFillsTheCacheForEveryone() throws Exception {
        var token = "upstreamtoken-" + UUID.randomUUID();
        UPSTREAM.acceptingOnlyBearer(token);
        var connected = connectedUser(token).mcpToken();
        var bystander = GatewayFixtures.mcpToken(componentClient, "bystander-" + UUID.randomUUID() + "@lightbend.com", List.of(),
                List.of(new UserSession.App(APP_ID, TokenMcpFixtures.NAME)));
        var before = UPSTREAM.requestsReceived();

        var tools = listedTools(connected);

        assertThat(tools).contains(GET_ITEM);
        assertThat(UPSTREAM.requestsReceived()).isGreaterThan(before);
        assertThat(listedTools(bystander)).contains(GET_ITEM);
    }
}
