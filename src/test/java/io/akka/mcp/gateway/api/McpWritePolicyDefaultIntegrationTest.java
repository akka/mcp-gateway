package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.McpAccessTokenEntity;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.application.SlackConnectionEntity;
import io.akka.mcp.gateway.application.UserSessionEntity;
import io.akka.mcp.gateway.domain.McpConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fresh deployment, where no admin has saved a selection yet: nothing may write anywhere.
 * Kept apart from the other write-policy tests because they all share, and change, one policy entity.
 */
public class McpWritePolicyDefaultIntegrationTest extends TestKitSupport {

    private static final String ADMIN_GROUP = "mcp-gateway-admin";
    private static final String READER_GROUP = "mcp-gateway-reader";
    private static final String WRITER_GROUP = "mcp-gateway-writer";
    private static final String EMAIL = "user@lightbend.com";

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                slack.mcp-url = "http://localhost:1/mcp"
                okta.groups.admin = "%s"
                okta.groups.reader = "%s"
                okta.groups.writer = "%s"
                """.formatted(ADMIN_GROUP, READER_GROUP, WRITER_GROUP));
    }

    private String browserSession(List<String> groups) {
        var token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(UserSessionEntity::create)
                .invoke(new UserSessionEntity.CreateCommand(
                        EMAIL, "User", Instant.now().plusSeconds(3600), groups, "", List.of()));
        return token;
    }

    @Test
    public void beforeAnyoneSavesAnything_noConnectorHasWritesEnabled() {
        var data = httpClient.GET("/admin/write-access/data")
                .addHeader("Cookie", "SESSION=" + browserSession(List.of(ADMIN_GROUP)))
                .responseBodyAs(McpWritePolicyEndpoint.WritePolicyResponse.class)
                .invoke().body();

        assertThat(data.connectors()).isNotEmpty();
        assertThat(data.connectors()).noneMatch(McpWritePolicyEndpoint.Connector::enabled);
        assertThat(data.updatedBy()).isNull();
    }

    @Test
    public void beforeAnyoneSavesAnything_aWriterCannotWriteAnywhere() throws Exception {
        var mcpToken = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(mcpToken)
                .method(McpAccessTokenEntity::create)
                .invoke(new McpAccessTokenEntity.CreateCommand(
                        EMAIL, "User", List.of(READER_GROUP, WRITER_GROUP), List.of(), "client-1", Instant.now().plusSeconds(3600)));
        componentClient.forKeyValueEntity(EMAIL)
                .method(SlackConnectionEntity::initiatePkceOAuth)
                .invoke(new SlackConnectionEntity.InitiateCommand("state-1", "verifier-1", "client-id"));
        componentClient.forKeyValueEntity(EMAIL)
                .method(SlackConnectionEntity::storeToken)
                .invoke(new SlackConnectionEntity.StoreTokenCommand(
                        "slack-access", "slack-refresh", Instant.now().plusSeconds(3600), "state-1"));
        componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::register)
                .invoke(new McpConfig("slack", "Slack", List.of(new McpConfig.ToolMeta(
                        "slack_post_message", "seeded", Map.of("type", "object", "properties", Map.of()), false, false))));

        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + mcpToken)
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", Map.of("name", "slack_post_message", "arguments", Map.of())))
                .responseBodyAs(String.class)
                .invoke();

        var text = JsonSupport.getObjectMapper().readTree(response.body())
                .path("result").path("content").get(0).path("text").asText();
        assertThat(text).contains("read-only");
    }
}
