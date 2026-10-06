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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A downstream MCP is read-only unless the operator lists it in {@code mcp.write-enabled}. That
 * gate is a property of the connector, not of the caller: holding the writer group does not buy a
 * write on a read-only MCP, and the writer group is still required on a write-enabled one.
 *
 * Okta admin stands in for a read-only MCP (not listed, and {@code isConnected} needs only a
 * non-blank url, so no OAuth seeding), Slack for a write-enabled one.
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
        // and write-enabled list have no checked-in defaults, so pin them.
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                okta-admin.mcp-url = "%s"
                slack.mcp-url = "%s"
                okta.groups.reader = "%s"
                okta.groups.writer = "%s"
                mcp.write-enabled = "slack"
                """.formatted(DEAD_URL, DEAD_URL, READER_GROUP, WRITER_GROUP));
    }

    /** An MCP client's Bearer token, what {@code POST /mcp} accepts. */
    private String createMcpToken(List<String> groups) {
        var token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(McpAccessTokenEntity::create)
                .invoke(new McpAccessTokenEntity.CreateCommand(
                        USER_EMAIL, "User", groups, List.of(), "client-1", Instant.now().plusSeconds(3600)));
        return token;
    }

    /** A browser session cookie value, what {@code GET /mcp/access} accepts. */
    private String createBrowserSession(List<String> groups) {
        var token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(UserSessionEntity::create)
                .invoke(new UserSessionEntity.CreateCommand(
                        USER_EMAIL, "User", Instant.now().plusSeconds(3600), groups, "", List.of()));
        return token;
    }

    private static McpConfig.ToolMeta toolMeta(String toolName, boolean readOnly) {
        return new McpConfig.ToolMeta(
                toolName, "seeded", Map.of("type", "object", "properties", Map.of()), readOnly, false);
    }

    /** Warm the registry so the tools resolve to their owning client and carry a classification. */
    private void seedTools(String mcpId, String mcpName, McpConfig.ToolMeta... tools) {
        componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::register)
                .invoke(new McpConfig(mcpId, mcpName, List.of(tools)));
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

    private List<String> listToolNames(List<String> groups) throws Exception {
        Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list");
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + createMcpToken(groups))
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();
        var names = new ArrayList<String>();
        JsonSupport.getObjectMapper().readTree(response.body()).path("result").path("tools")
                .forEach(t -> names.add(t.path("name").asText()));
        return names;
    }

    private static String resultText(String responseBody) throws Exception {
        return JsonSupport.getObjectMapper().readTree(responseBody)
                .path("result").path("content").get(0).path("text").asText();
    }

    /** The point of the feature: the connector's read-only status outranks the user's writer group. */
    @Test
    public void writeToolOnReadOnlyMcp_isRefusedEvenForWriter() throws Exception {
        seedTools("okta-admin", "Okta", toolMeta("okta_create_user", false));

        var json = JsonSupport.getObjectMapper().readTree(callTool("okta_create_user", List.of(WRITER_GROUP)));

        assertThat(json.path("result").path("isError").asBoolean()).isTrue();
        var text = json.path("result").path("content").get(0).path("text").asText();
        assertThat(text).contains("read-only").contains("okta_create_user");
        // Refused as a connector-level policy, not as a permissions problem with this user.
        assertThat(text).doesNotContain("not permitted for your role");
    }

    /** An unregistered tool defaults to write; the refusal must say it is unclassified, not that it is a write. */
    @Test
    public void unclassifiedToolOnReadOnlyMcp_saysSoInsteadOfCallingItAWrite() throws Exception {
        var text = resultText(callTool("okta_never_registered", List.of(READER_GROUP, WRITER_GROUP)));

        assertThat(text).contains("not been classified").contains("howto_refresh_tools");
        assertThat(text).doesNotContain("write tools such as");
    }

    /** Reads on a read-only MCP are untouched by the gate. */
    @Test
    public void readToolOnReadOnlyMcp_passesTheWriteGate() throws Exception {
        seedTools("okta-admin", "Okta", toolMeta("okta_list_users", true));

        var text = resultText(callTool("okta_list_users", List.of(READER_GROUP)));

        // Reaches the upstream (and fails there, DEAD_URL), which is what proves it got past the gate.
        assertThat(text).contains("Connection refused");
    }

    /** A write-enabled MCP lets the write through the connector gate to the role check. */
    @Test
    public void writeToolOnWriteEnabledMcp_passesTheWriteGate() throws Exception {
        connectSlack();
        seedTools("slack", "Slack", toolMeta("slack_post_message", false));

        var text = resultText(callTool("slack_post_message", List.of(WRITER_GROUP)));

        assertThat(text).contains("Connection refused");
    }

    /** The connector gate is additional, not a replacement: the writer group is still required. */
    @Test
    public void writeToolOnWriteEnabledMcp_stillRequiresWriterGroup() throws Exception {
        connectSlack();
        seedTools("slack", "Slack", toolMeta("slack_post_message", false));

        var json = JsonSupport.getObjectMapper().readTree(callTool("slack_post_message", List.of(READER_GROUP)));

        assertThat(json.path("error").path("message").asText()).isEqualTo("Write access not permitted");
    }

    /** Advertising tools that will always be refused only wastes the model's turns. */
    @Test
    public void toolsList_hidesWriteToolsOfReadOnlyMcp_evenFromWriters() throws Exception {
        seedTools("okta-admin", "Okta", toolMeta("okta_list_users", true), toolMeta("okta_create_user", false));

        var names = listToolNames(List.of(READER_GROUP, WRITER_GROUP));

        assertThat(names).contains("okta_list_users").doesNotContain("okta_create_user");
    }

    @Test
    public void toolsList_showsWriteToolsOfWriteEnabledMcp_onlyToWriters() throws Exception {
        seedTools("slack", "Slack", toolMeta("slack_search_messages", true), toolMeta("slack_post_message", false));

        var writerView = listToolNames(List.of(READER_GROUP, WRITER_GROUP));
        var readerView = listToolNames(List.of(READER_GROUP));

        assertThat(writerView).contains("slack_search_messages", "slack_post_message");
        assertThat(readerView).contains("slack_search_messages").doesNotContain("slack_post_message");
    }

    /** The dashboard badge must not promise writes the signed-in user cannot perform. */
    @Test
    public void mcpAccess_reportsWriteOnlyWhereTheCallerCanActuallyWrite() {
        var writer = accessFor(List.of(READER_GROUP, WRITER_GROUP));
        var reader = accessFor(List.of(READER_GROUP));

        assertThat(writer.get("slack")).isTrue();
        assertThat(writer.get("okta-admin")).isFalse();
        assertThat(reader.get("slack")).isFalse();
    }

    private Map<String, Boolean> accessFor(List<String> groups) {
        var response = httpClient.GET("/mcp/access")
                .addHeader("Cookie", "SESSION=" + createBrowserSession(groups))
                .responseBodyAs(AkkaMcpGateway.McpAccessResponse.class)
                .invoke();
        var byId = new java.util.HashMap<String, Boolean>();
        response.body().accessible().forEach(e -> byId.put(e.mcpId(), e.writeAllowed()));
        response.body().inaccessible().forEach(e -> byId.put(e.mcpId(), e.writeAllowed()));
        return byId;
    }
}
