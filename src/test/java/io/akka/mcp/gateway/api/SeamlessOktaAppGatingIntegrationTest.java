package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
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
 * Once an operator sets {@code SEAMLESS_OKTA_APP_ID}, Seamless.AI is gated by that Okta
 * application assignment exactly like every other system — see
 * {@code AkkaMcpGatewayOktaAppGatingIntegrationTest} for the same behaviour against Okta admin
 * lookups. {@link SeamlessAccessIntegrationTest} covers the default, ungated configuration.
 */
public class SeamlessOktaAppGatingIntegrationTest extends TestKitSupport {

    private static final String OKTA_APP_ID = "seamless-app-id-under-test";
    private static final String MCP_ID = "seamless";
    private static final String TOOL_NAME = "Seamless_search_contacts";

    @Override
    protected TestKit.Settings testKitSettings() {
        // seamless.okta-app-id has no checked-in default; pin it so app-gating is deterministic.
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                seamless.okta-app-id = "%s"
                """.formatted(OKTA_APP_ID));
    }

    /** An MCP client's Bearer token — what {@code POST /mcp} actually accepts. */
    private String createMcpToken(List<UserSession.App> apps) {
        var token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(McpAccessTokenEntity::create)
                .invoke(new McpAccessTokenEntity.CreateCommand(
                        "user@lightbend.com", "User", List.of(), apps, "client-1", Instant.now().plusSeconds(3600)));
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

    @Test
    public void toolsList_hidesSeamlessToolsForUserWithoutTheApp() throws Exception {
        seedCachedSeamlessTool();
        var token = createMcpToken(List.of());
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
    public void toolsList_surfacesSeamlessToolsForUserWithTheApp() throws Exception {
        seedCachedSeamlessTool();
        var token = createMcpToken(List.of(new UserSession.App(OKTA_APP_ID, "Seamless.AI")));
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
    public void toolsCall_rejectsSeamlessToolForUserWithoutTheApp() throws Exception {
        seedCachedSeamlessTool();
        var token = createMcpToken(List.of());
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
