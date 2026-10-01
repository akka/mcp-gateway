package io.akka.mcp.gateway.api;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.UserSessionEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression coverage for the dashboard bug where the Seamless.AI card always showed "connected"
 * regardless of whether the operator had actually set {@code SEAMLESS_API_KEY} (the
 * {@code STATUS_FETCHERS['seamless']} entry hardcoded the dot instead of fetching real status).
 * This class covers the default configuration (no {@code seamless.api-key} set); see
 * {@code SeamlessStatusEndpointConfiguredTest} for the configured case.
 */
public class SeamlessStatusEndpointTest extends TestKitSupport {

    private String createSession(String email, List<String> groups) {
        var token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(UserSessionEntity::create)
                .invoke(new UserSessionEntity.CreateCommand(
                        email, "User", Instant.now().plusSeconds(3600), groups, "", List.of()));
        return token;
    }

    @Test
    public void status_withoutSession_isRejected() {
        assertThrows(Exception.class, () ->
                httpClient.GET("/seamless/status").responseBodyAs(String.class).invoke());
    }

    @Test
    public void status_reportsNotConnectedWhenApiKeyIsNotConfigured() {
        // seamless.api-key has no checked-in default and this test leaves it unset.
        var token = createSession("seamless-status-test@lightbend.com", List.of());

        var response = httpClient.GET("/seamless/status")
                .addHeader("Cookie", "SESSION=" + token)
                .responseBodyAs(SeamlessStatusEndpoint.Status.class)
                .invoke();

        assertThat(response.status().isSuccess()).isTrue();
        assertThat(response.body().connected()).isFalse();
    }
}
