package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.McpAccessTokenEntity;
import io.akka.mcp.gateway.application.McpRegistryEntity;
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
 * Seamless.AI is gated by an Okta application assignment the same way every
 * other system is
 * ({@code SEAMLESS_OKTA_APP_ID}, blank disables the gate — see
 * {@code SeamlessMcpClient}), on top
 * of the usual read/write permission check. This class covers the default,
 * ungated configuration
 * (no {@code seamless.okta-app-id} set); see
 * {@code SeamlessOktaAppGatingIntegrationTest} for the
 * behaviour once an operator configures one. Seamless still authenticates
 * upstream with a single
 * shared API key rather than a per-user connection, so a user who does hold the
 * app has nothing
 * further to connect.
 */
public class SeamlessAccessIntegrationTest extends TestKitSupport {

    private static final String MCP_ID = "seamless";
    private static final String TOOL_NAME = "Seamless_search_contacts";

    /** An MCP client's Bearer token — what {@code POST /mcp} actually accepts. */
    private String createMcpTokenWithNoApps() {
        var token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(McpAccessTokenEntity::create)
                .invoke(new McpAccessTokenEntity.CreateCommand(
                        "user@lightbend.com", "User", List.of(), List.<UserSession.App>of(), "client-1",
                        Instant.now().plusSeconds(3600)));
        return token;
    }

    private void seedCachedSeamlessTool() {
        var cachedTool = new McpConfig.ToolMeta(
                TOOL_NAME, "Search Seamless contacts",
                Map.of("type", "object", "properties", Map.of()), true, false);
        componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::register)
                .invoke(new McpConfig(MCP_ID, "Seamless.AI", List.of(cachedTool)));
    }

    private List<String> listToolNames(String token) throws Exception {
        Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list");
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + token)
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();
        var tools = JsonSupport.getObjectMapper().readTree(response.body()).path("result").path("tools");
        var names = new ArrayList<String>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        return names;
    }

    @Test
    public void toolsList_surfacesSeamlessToolsForAnyAuthenticatedUser_whenNoAppIdIsConfigured() throws Exception {
        // seamless.okta-app-id has no checked-in default (blank = ungated) and this
        // test leaves
        // it unset, so a user with no app assignments at all still sees the tool.
        seedCachedSeamlessTool();
        var token = createMcpTokenWithNoApps();
        assertThat(listToolNames(token)).contains(TOOL_NAME);
    }

    @Test
    public void toolsCall_tellsAnyUserToWaitForOperatorWhenApiKeyNotConfigured() throws Exception {
        seedCachedSeamlessTool();
        // seamless.api-key is unset by default in test config, so even this fully
        // unprivileged
        // (no-app) session still can't call the tool — the message is about the
        // operator's
        // configuration, not about the user's own access.
        var token = createMcpTokenWithNoApps();
        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", TOOL_NAME, "arguments", Map.of()));
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + token)
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();
        var json = JsonSupport.getObjectMapper().readTree(response.body());
        var result = json.path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText())
                .contains("Seamless.AI is not connected");
    }
}
