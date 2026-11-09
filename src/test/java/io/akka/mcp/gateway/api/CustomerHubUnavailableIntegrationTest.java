package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.CustomerHubMcpClient;
import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A token is only kept after the hub has accepted it, so a hub that cannot be reached keeps nothing. */
public class CustomerHubUnavailableIntegrationTest extends TestKitSupport {

    private static final String APP_ID = "customer-hub-unavailable-app";
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
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                customer-hub.mcp-url = "http://127.0.0.1:%d"
                customer-hub.okta-app-id = "%s"
                """.formatted(CLOSED_PORT, APP_ID));
    }

    @Test
    public void connecting_whenTheHubCannotBeReached_isReportedAsUnreachable_andKeepsNothing() throws Exception {
        var session = GatewayFixtures.browserSession(componentClient, "unreachable@lightbend.com", List.of(),
                List.of(new UserSession.App(APP_ID, CustomerHubMcpClient.MCP_NAME)));

        var response = httpClient.POST("/customer-hub/connect")
                .addHeader("Cookie", "SESSION=" + session)
                .withRequestBody(new CustomerHubEndpoint.ConnectRequest("hubtoken-abc"))
                .invoke();

        assertThat(response.status().intValue()).isEqualTo(502);
        assertThat(response.body().utf8String()).contains("could not be reached");
        var status = httpClient.GET("/customer-hub/status").addHeader("Cookie", "SESSION=" + session).invoke();
        assertThat(JsonSupport.getObjectMapper().readTree(status.body().utf8String()).path("connected").asBoolean()).isFalse();
    }
}
