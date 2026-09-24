package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.application.UserSessionEntity;
import io.akka.mcp.gateway.domain.McpConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class GoogleWorkspaceEndpointIntegrationTest extends TestKitSupport {

    private String createSession(List<String> groups) {
        var token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(UserSessionEntity::create)
                .invoke(new UserSessionEntity.CreateCommand(
                        "user@lightbend.com", "User", Instant.now().plusSeconds(3600), groups, "", List.of()));
        return token;
    }

    @Test
    public void workspaceStatus_withSession_returnsDisconnected() throws Exception {
        var token = createSession(List.of());
        var response = httpClient.GET("/googleworkspace/oauth/status")
                .addHeader("Authorization", "Bearer " + token)
                .responseBodyAs(String.class)
                .invoke();
        assertThat(response.status().isSuccess()).isTrue();
        var json = JsonSupport.getObjectMapper().readTree(response.body());
        assertThat(json.path("connected").asBoolean()).isFalse();
    }

    /**
     * The old Docs-only OAuth path was replaced by the Google Workspace endpoint. Any client that still
     * points at /googledocs/oauth/* must not silently be answered — the route should be unregistered.
     */
    @Test
    public void oldGoogleDocsEndpoint_isNoLongerServed() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/googledocs/oauth/status").responseBodyAs(String.class).invoke()
        );
        assertThat(ex.getMessage()).contains("404");
    }

    /**
     * The four Workspace MCP clients (Drive/Docs/Gmail/Calendar) each register under their own mcpId
     * but share the single GoogleWorkspaceConnectionEntity for authentication. When their tools are
     * warmed into the registry cache, tools/list must surface tools from all four — this is the
     * evidence that one OAuth grant fans out to the four downstream Google MCP servers.
     */
    @Test
    public void toolsList_surfacesCachedToolsForAllFourWorkspaceMcps() throws Exception {
        record Cached(String mcpId, String mcpName, String toolName) {}
        var cached = List.of(
                new Cached("google-workspace-drive",    "Google Workspace — Drive",    "Workspace_GoogleDrive_search_files"),
                new Cached("google-workspace-docs",     "Google Workspace — Docs",     "Workspace_GoogleDocs_read_doc"),
                new Cached("google-workspace-gmail",    "Google Workspace — Gmail",    "Workspace_Gmail_search_threads"),
                new Cached("google-workspace-calendar", "Google Workspace — Calendar", "Workspace_GoogleCalendar_list_events"));

        for (var c : cached) {
            var toolMeta = new McpConfig.ToolMeta(
                    c.toolName(), "cached", Map.of("type", "object", "properties", Map.of()), true, false);
            componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                    .method(McpRegistryEntity::register)
                    .invoke(new McpConfig(c.mcpId(), c.mcpName(), List.of(toolMeta)));
        }

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
        assertThat(names).contains(
                "Workspace_GoogleDrive_search_files",
                "Workspace_GoogleDocs_read_doc",
                "Workspace_Gmail_search_threads",
                "Workspace_GoogleCalendar_list_events");
    }
}
