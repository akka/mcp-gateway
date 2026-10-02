package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.McpAccessTokenEntity;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.application.SlackConnectionEntity;
import io.akka.mcp.gateway.domain.McpConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A downstream MCP is read-only unless it opts in via {@link
 * io.akka.mcp.gateway.application.RemoteMcpClient#allowsWrites()}. That gate is a property of the
 * connector, not of the caller: holding the writer group does not buy a write on a read-only MCP.
 *
 * Okta admin stands in for a read-only MCP (no opt-in, and {@code isConnected} needs only a
 * non-blank url, so no OAuth seeding), Slack for a write-allowed one.
 */
public class AkkaMcpGatewayWriteGateIntegrationTest extends TestKitSupport {

    private static final String WRITER_GROUP = "mcp-gateway-writer";
    private static final String READER_GROUP = "mcp-gateway-reader";
    private static final String USER_EMAIL = "user@lightbend.com";

    // Unroutable on purpose: a refused call must never reach an upstream, and a call that does get
    // through must fail on the network rather than silently hitting a real server.
    private static final String DEAD_URL = "http://localhost:1/mcp";

    @Override
    protected TestKit.Settings testKitSettings() {
        // Both urls must be non-blank or the clients report themselves disconnected and the
        // "not connected" branch answers before the write gate is ever reached. The group names
        // have no checked-in defaults, so pin them to exercise the role check downstream of it.
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                okta-admin.mcp-url = "%s"
                slack.mcp-url = "%s"
                okta.groups.reader = "%s"
                okta.groups.writer = "%s"
                """.formatted(DEAD_URL, DEAD_URL, READER_GROUP, WRITER_GROUP));
    }

    /** An MCP client's Bearer token — what {@code POST /mcp} accepts. */
    private String createMcpToken(List<String> groups) {
        var token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(McpAccessTokenEntity::create)
                .invoke(new McpAccessTokenEntity.CreateCommand(
                        USER_EMAIL, "User", groups, List.of(), "client-1", Instant.now().plusSeconds(3600)));
        return token;
    }

    /** Warm the registry so the tool resolves to its owning client and carries a classification. */
    private void seedTool(String mcpId, String mcpName, String toolName, boolean readOnly) {
        var meta = new McpConfig.ToolMeta(
                toolName, "seeded", Map.of("type", "object", "properties", Map.of()), readOnly, false);
        componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::register)
                .invoke(new McpConfig(mcpId, mcpName, List.of(meta)));
    }

    private void connectSlack() {
        componentClient.forKeyValueEntity(USER_EMAIL)
                .method(SlackConnectionEntity::initiatePkceOAuth)
                .invoke(new SlackConnectionEntity.InitiateCommand("state-1", "verifier-1", "client-id"));
        componentClient.forKeyValueEntity(USER_EMAIL)
                .method(SlackConnectionEntity::storeToken)
                .invoke(new SlackConnectionEntity.StoreTokenCommand(
                        "slack-access", "slack-refresh", Instant.now().plusSeconds(3600), "state-1"));
    }

    private String callTool(String toolName, List<String> groups) throws Exception {
        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", Map.of()));
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + createMcpToken(groups))
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();
        return response.body();
    }

    /** The point of the feature: the connector's read-only status outranks the user's writer group. */
    @Test
    public void writeToolOnReadOnlyMcp_isRefusedEvenForWriter() throws Exception {
        seedTool("okta-admin", "Okta", "okta_create_user", false);

        var json = JsonSupport.getObjectMapper().readTree(callTool("okta_create_user", List.of(WRITER_GROUP)));

        assertThat(json.path("result").path("isError").asBoolean()).isTrue();
        var text = json.path("result").path("content").get(0).path("text").asText();
        assertThat(text).contains("read-only").contains("okta_create_user");
        // Refused as a connector-level policy, not as a permissions problem with this user.
        assertThat(text).doesNotContain("not permitted for your role");
    }

    /** Reads on a read-only MCP are untouched by the gate. */
    @Test
    public void readToolOnReadOnlyMcp_passesTheWriteGate() throws Exception {
        seedTool("okta-admin", "Okta", "okta_list_users", true);

        var json = JsonSupport.getObjectMapper().readTree(callTool("okta_list_users", List.of(READER_GROUP)));

        // Reaches the upstream (and fails there, DEAD_URL), which is what proves it got past the gate.
        assertThat(json.path("result").path("content").get(0).path("text").asText())
                .doesNotContain("read-only");
    }

    /** A write-allowed MCP lets the write through the connector gate to the role check. */
    @Test
    public void writeToolOnWriteAllowedMcp_passesTheWriteGate() throws Exception {
        connectSlack();
        seedTool("slack", "Slack", "slack_post_message", false);

        var json = JsonSupport.getObjectMapper().readTree(callTool("slack_post_message", List.of(WRITER_GROUP)));

        assertThat(json.path("result").path("content").get(0).path("text").asText())
                .doesNotContain("read-only");
    }

    /** The new gate is additional, not a replacement: the writer group is still required. */
    @Test
    public void writeToolOnWriteAllowedMcp_stillRequiresWriterGroup() throws Exception {
        connectSlack();
        seedTool("slack", "Slack", "slack_post_message", false);

        var json = JsonSupport.getObjectMapper().readTree(callTool("slack_post_message", List.of(READER_GROUP)));

        assertThat(json.path("error").path("message").asText()).isEqualTo("Write access not permitted");
    }
}
