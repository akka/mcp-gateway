package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import io.akka.mcp.gateway.testsupport.TokenMcpFixtures;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A token is only kept after the upstream has accepted it, so an upstream that cannot be reached keeps nothing. */
public class TokenMcpUnavailableIntegrationTest extends TestKitSupport {

    private static final String APP_ID = "token-service-unavailable-app";
    private static final int CLOSED_PORT = closedPort();

    private static int closedPort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Could not find a free port", e);
        }
    }

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig(
                TokenMcpFixtures.setting("http://127.0.0.1:" + CLOSED_PORT, APP_ID));
    }

    @Test
    public void connecting_whenTheUpstreamCannotBeReached_isReportedAsUnreachable_andKeepsNothing() throws Exception {
        var session = GatewayFixtures.browserSession(componentClient, "unreachable@lightbend.com", List.of(),
                List.of(new UserSession.App(APP_ID, TokenMcpFixtures.NAME)));

        var response = httpClient.POST(TokenMcpFixtures.path("connect"))
                .addHeader("Cookie", "SESSION=" + session)
                .withRequestBody(new TokenMcpEndpoint.ConnectRequest("upstreamtoken-abc"))
                .invoke();

        assertThat(response.status().intValue()).isEqualTo(502);
        assertThat(response.body().utf8String()).contains("could not be reached");
        var status = httpClient.GET(TokenMcpFixtures.path("status")).addHeader("Cookie", "SESSION=" + session).invoke();
        assertThat(JsonSupport.getObjectMapper().readTree(status.body().utf8String()).path("connected").asBoolean()).isFalse();
    }
}
