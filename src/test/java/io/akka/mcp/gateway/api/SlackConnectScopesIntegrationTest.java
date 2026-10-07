package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starting the Slack sign-in through the real endpoint. Slack asks every user for the same scopes
 * whatever their role or the write policy, as the other connectors do, so enabling writes or gaining
 * the writer role never needs a reconnect: the write gate decides who may post. If the scopes were
 * made to depend on role or policy again, each such change would force users to reconnect.
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

    private List<String> scopesRequestedBy(List<String> groups) {
        var session = GatewayFixtures.browserSession(componentClient, "user-" + UUID.randomUUID() + "@lightbend.com", groups);

        var response = httpClient.GET("/slack/oauth/connect")
                .addHeader("Cookie", "SESSION=" + session)
                .invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        var location = response.httpResponse().getHeader("Location").orElseThrow().value();
        return Arrays.stream(URI.create(location).getRawQuery().split("&"))
                .filter(pair -> pair.startsWith("user_scope="))
                .map(pair -> URLDecoder.decode(pair.substring("user_scope=".length()), StandardCharsets.UTF_8))
                .flatMap(scopes -> Arrays.stream(scopes.split(" ")))
                .toList();
    }

    @Test
    public void aWriter_isAskedToGrantPosting_whetherOrNotSlackIsEnabled() {
        GatewayFixtures.enableWritesOn(componentClient, List.of("slack"));
        var whenEnabled = scopesRequestedBy(List.of(READER_GROUP, WRITER_GROUP));
        GatewayFixtures.enableWritesOn(componentClient, List.of());
        var whenNotEnabled = scopesRequestedBy(List.of(READER_GROUP, WRITER_GROUP));

        assertThat(whenEnabled).contains("search:read", "chat:write");
        assertThat(whenNotEnabled).containsExactlyInAnyOrderElementsOf(whenEnabled);
    }

    @Test
    public void aReader_isAskedForTheSameScopesAsAWriter() {
        GatewayFixtures.enableWritesOn(componentClient, List.of("slack"));

        var reader = scopesRequestedBy(List.of(READER_GROUP));
        var writer = scopesRequestedBy(List.of(READER_GROUP, WRITER_GROUP));

        assertThat(reader).contains("chat:write");
        assertThat(reader).containsExactlyInAnyOrderElementsOf(writer);
    }

    @Test
    public void aUserWithNoRole_isAskedForTheSameScopesToo() {
        var noRole = scopesRequestedBy(List.of());
        var writer = scopesRequestedBy(List.of(READER_GROUP, WRITER_GROUP));

        assertThat(noRole).contains("chat:write");
        assertThat(noRole).containsExactlyInAnyOrderElementsOf(writer);
    }
}
