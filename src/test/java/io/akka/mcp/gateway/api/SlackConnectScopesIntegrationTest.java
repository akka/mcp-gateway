package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.McpWritePolicyEntity;
import io.akka.mcp.gateway.application.UserSessionEntity;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starting the Slack sign-in through the real endpoint: {@code chat:write} is only requested from a
 * user who could actually post. This checks the wiring from the connect request to the scopes Slack
 * is asked for, which the pure scope function test cannot.
 */
public class SlackConnectScopesIntegrationTest extends TestKitSupport {

    private static final String READER_GROUP = "mcp-gateway-reader";
    private static final String WRITER_GROUP = "mcp-gateway-writer";

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                slack.oauth.client-id = "test-client-id"
                slack.oauth.redirect-uri = "http://localhost/slack/oauth/callback"
                okta.groups.reader = "%s"
                okta.groups.writer = "%s"
                """.formatted(READER_GROUP, WRITER_GROUP));
    }

    private void enableWritesOn(List<String> mcpIds) {
        var current = componentClient.forKeyValueEntity(McpWritePolicyEntity.ENTITY_ID)
                .method(McpWritePolicyEntity::get)
                .invoke();
        componentClient.forKeyValueEntity(McpWritePolicyEntity.ENTITY_ID)
                .method(McpWritePolicyEntity::select)
                .invoke(new McpWritePolicyEntity.SelectCommand(mcpIds, current.version(), "test-setup"));
    }

    private List<String> scopesRequestedBy(List<String> groups) {
        var session = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(session)
                .method(UserSessionEntity::create)
                .invoke(new UserSessionEntity.CreateCommand(
                        "user-" + UUID.randomUUID() + "@lightbend.com", "User",
                        Instant.now().plusSeconds(3600), groups, "", List.of()));

        var response = httpClient.GET("/slack/oauth/connect")
                .addHeader("Cookie", "SESSION=" + session)
                .invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        var location = response.httpResponse().getHeader("Location").orElseThrow().value();
        return userScopes(location);
    }

    private static List<String> userScopes(String location) {
        return Arrays.stream(URI.create(location).getRawQuery().split("&"))
                .filter(pair -> pair.startsWith("user_scope="))
                .map(pair -> URLDecoder.decode(pair.substring("user_scope=".length()), StandardCharsets.UTF_8))
                .flatMap(scopes -> Arrays.stream(scopes.split(" ")))
                .toList();
    }

    @Test
    public void aWriter_isAskedToGrantPosting_whenSlackIsEnabled() {
        enableWritesOn(List.of("slack"));

        assertThat(scopesRequestedBy(List.of(READER_GROUP, WRITER_GROUP))).contains("search:read", "chat:write");
    }

    @Test
    public void aReader_isNeverAskedToGrantPosting() {
        enableWritesOn(List.of("slack"));

        assertThat(scopesRequestedBy(List.of(READER_GROUP))).contains("search:read").doesNotContain("chat:write");
    }

    @Test
    public void aWriter_isNotAskedToGrantPosting_whenSlackIsNotEnabled() {
        enableWritesOn(List.of());

        assertThat(scopesRequestedBy(List.of(READER_GROUP, WRITER_GROUP))).contains("search:read").doesNotContain("chat:write");
    }
}
