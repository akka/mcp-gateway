package io.akka.mcp.gateway.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.UserSessionEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SeamlessStatusEndpoint} once the operator has set {@code SEAMLESS_API_KEY} and
 * {@code SEAMLESS_MCP_URL}; see {@code SeamlessStatusEndpointTest} for the unconfigured case.
 */
public class SeamlessStatusEndpointConfiguredTest extends TestKitSupport {

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                seamless.api-key = "test-key"
                seamless.mcp-url = "https://mcp.seamless.ai/mcp"
                """);
    }

    private String createSession(String email, List<String> groups) {
        var token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(UserSessionEntity::create)
                .invoke(new UserSessionEntity.CreateCommand(
                        email, "User", Instant.now().plusSeconds(3600), groups, "", List.of()));
        return token;
    }

    @Test
    public void status_reportsConnectedWhenApiKeyIsConfigured() {
        var token = createSession("seamless-status-test-configured@lightbend.com", List.of());

        var response = httpClient.GET("/seamless/status")
                .addHeader("Cookie", "SESSION=" + token)
                .responseBodyAs(SeamlessStatusEndpoint.Status.class)
                .invoke();

        assertThat(response.status().isSuccess()).isTrue();
        assertThat(response.body().connected()).isTrue();
    }
}
