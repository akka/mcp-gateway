package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.application.UserSessionEntity;
import io.akka.mcp.gateway.domain.McpConfig;
import io.akka.mcp.gateway.domain.UserSession;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Okta app assignment gate ({@code GET /mcp/access}) was dashboard-only cosmetics —
 * {@code POST /mcp} (tools/list, tools/call) never evaluated {@code session.hasApp(...)}, so any
 * {@code mcp-gateway-reader} could enumerate and call {@code okta_*} tools without the "Okta MCP
 * Admin" app assigned, because {@code OktaMcpClient} authenticates with a single org-wide token
 * and has no other way to gate access per-user.
 */
public class AkkaMcpGatewayOktaAppGatingIntegrationTest extends TestKitSupport {

    private static final String OKTA_APP_ID = "okta-admin-app-id-under-test";
    private static final String MCP_ID = "okta-admin";
    private static final String TOOL_NAME = "okta_list_users";

    @Override
    protected TestKit.Settings testKitSettings() {
        // okta-admin.okta-app-id has no checked-in default; pin it so app-gating is deterministic.
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                okta-admin.okta-app-id = "%s"
                """.formatted(OKTA_APP_ID));
    }

    private String createSession(List<UserSession.App> apps) {
        var token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(UserSessionEntity::create)
                .invoke(new UserSessionEntity.CreateCommand(
                        "user@lightbend.com", "User", Instant.now().plusSeconds(3600), List.of(), "", apps));
        return token;
    }

    private void seedCachedOktaTool() {
        var cachedTool = new McpConfig.ToolMeta(
                TOOL_NAME, "List Okta users",
                Map.of("type", "object", "properties", Map.of()), true, false);
        componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::register)
                .invoke(new McpConfig(MCP_ID, "Okta", List.of(cachedTool)));
    }

    @Test
    public void toolsList_hidesOktaToolsForUserWithoutTheApp() throws Exception {
        seedCachedOktaTool();
        var token = createSession(List.of());
        Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list");

        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + token)
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();

        var tools = JsonSupport.getObjectMapper().readTree(response.body()).path("result").path("tools");
        var names = new ArrayList<String>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        assertThat(names).doesNotContain(TOOL_NAME);
    }

    @Test
    public void toolsList_surfacesOktaToolsForUserWithTheApp() throws Exception {
        seedCachedOktaTool();
        var token = createSession(List.of(new UserSession.App(OKTA_APP_ID, "Okta MCP Admin")));
        Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list");

        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + token)
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();

        var tools = JsonSupport.getObjectMapper().readTree(response.body()).path("result").path("tools");
        var names = new ArrayList<String>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        assertThat(names).contains(TOOL_NAME);
    }

    @Test
    public void toolsCall_rejectsOktaToolForUserWithoutTheApp() throws Exception {
        seedCachedOktaTool();
        var token = createSession(List.of());
        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", TOOL_NAME, "arguments", Map.of()));

        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + token)
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();

        var json = JsonSupport.getObjectMapper().readTree(response.body());
        // Denied the same way as "no client can handle this tool" — the reader never learns
        // the tool exists behind an unassigned app.
        assertThat(json.path("error").path("message").asText()).contains("No MCP client can handle tool");
    }
}
