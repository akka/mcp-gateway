package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.testsupport.FakeMcpServer;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.AdvertisedTool;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import io.akka.mcp.gateway.testsupport.TokenMcpFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** A server that lists more tools than the gateway will cache has its listing cut, however many it offers. */
public class TokenMcpListingLimitsIntegrationTest extends TestKitSupport {

    private static final String GATEWAY_ORIGIN = "http://localhost:9000";
    private static final String APP_ID = "listing-limits-app-under-test";
    private static final int OFFERED = McpRegistryEntity.State.MAX_ADDED_TOOLS_PER_SYSTEM + 20;
    private static final FakeMcpServer UPSTREAM = FakeMcpServer.start().advertising(
            IntStream.range(0, OFFERED).mapToObj(i -> new AdvertisedTool("tool_" + i, true)).toArray(AdvertisedTool[]::new));

    @AfterAll
    public static void stopUpstream() {
        UPSTREAM.close();
    }

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                %s
                mcp.base-url = "%s"
                """.formatted(TokenMcpFixtures.setting(UPSTREAM.url(), APP_ID), GATEWAY_ORIGIN));
    }

    @Test
    public void aListingOverTheLimit_isCutAtTheLimit() throws Exception {
        var token = "token-" + UUID.randomUUID();
        UPSTREAM.acceptingOnlyBearer(token);
        var email = "listing-limits-" + UUID.randomUUID() + "@lightbend.com";
        var session = GatewayFixtures.browserSession(componentClient, email, List.of(),
                List.of(new UserSession.App(APP_ID, TokenMcpFixtures.NAME)));

        var response = httpClient.POST(TokenMcpFixtures.path("connect"))
                .addHeader("Cookie", "SESSION=" + session)
                .addHeader("Origin", GATEWAY_ORIGIN)
                .withRequestBody(new TokenMcpEndpoint.ConnectRequest(token))
                .invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
        var tools = JsonSupport.getObjectMapper().readTree(response.body().utf8String()).path("tools");
        assertThat(tools).hasSize(McpRegistryEntity.State.MAX_ADDED_TOOLS_PER_SYSTEM);
    }
}
