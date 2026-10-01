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

/**
 * Seamless.AI has no Okta application gate — by explicit user decision, it is available to any
 * authenticated gateway user (subject to the usual read/write permission check), since it
 * already authenticates upstream with a single shared API key rather than a per-user connection.
 * Availability is purely an operator-configuration question (is the API key set), never a
 * per-user application-assignment question.
 */
public class SeamlessAccessIntegrationTest extends TestKitSupport {

    private static final String MCP_ID = "seamless";
    private static final String TOOL_NAME = "Seamless_search_contacts";

    private String createSessionWithNoApps() {
        var token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(UserSessionEntity::create)
                .invoke(new UserSessionEntity.CreateCommand(
                        "user@lightbend.com", "User", Instant.now().plusSeconds(3600), List.of(), "", List.of()));
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
    public void toolsList_surfacesSeamlessToolsForAnyAuthenticatedUser_withNoAppAssignmentAtAll() throws Exception {
        seedCachedSeamlessTool();
        var token = createSessionWithNoApps();
        assertThat(listToolNames(token)).contains(TOOL_NAME);
    }

    @Test
    public void toolsCall_tellsAnyUserToWaitForOperatorWhenApiKeyNotConfigured() throws Exception {
        seedCachedSeamlessTool();
        // seamless.api-key is unset by default in test config, so even this fully unprivileged
        // (no-app) session still can't call the tool — the message is about the operator's
        // configuration, not about the user's own access.
        var token = createSessionWithNoApps();
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
