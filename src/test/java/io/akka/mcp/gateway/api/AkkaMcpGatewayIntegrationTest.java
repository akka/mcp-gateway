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

public class AkkaMcpGatewayIntegrationTest extends TestKitSupport {

    private String createSession(List<String> groups) {
        var token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(UserSessionEntity::create)
                .invoke(new UserSessionEntity.CreateCommand(
                        "user@lightbend.com", "User", Instant.now().plusSeconds(3600), groups, "", List.of()));
        return token;
    }

    /**
     * With no services connected and an empty registry cache, every upstream contributes nothing —
     * yet the list must never come back empty, and must always carry the self-help tool so a stuck
     * user can recover. This is the guarantee that was violated in the empty-tools incident.
     */
    @Test
    public void toolsList_withNoConnectedServices_stillReturnsHowToTools() throws Exception {
        var token = createSession(List.of());
        Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list");

        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + token)
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();

        assertThat(response.status().isSuccess()).isTrue();

        var tools = JsonSupport.getObjectMapper().readTree(response.body())
                .path("result").path("tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools.size()).isGreaterThan(0);

        var names = new ArrayList<String>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        assertThat(names).contains("howto_refresh_tools");
    }

    /**
     * Once the registry cache has been warmed for a service (which warm-on-connect does), its tools
     * must still appear in tools/list even though the service is disconnected in this test and its
     * live fetch would fail. This is the payoff of a warm cache: graceful degradation, not emptiness.
     */
    @Test
    public void toolsList_surfacesCachedToolsForDisconnectedService() throws Exception {
        var cachedTool = new McpConfig.ToolMeta(
                "ZohoDesk_getTickets", "List support tickets",
                Map.of("type", "object", "properties", Map.of()), true, false);
        componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::register)
                .invoke(new McpConfig("zoho-desk", "Zoho Desk", List.of(cachedTool)));

        var token = createSession(List.of());
        Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list");

        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + token)
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();

        var tools = JsonSupport.getObjectMapper().readTree(response.body())
                .path("result").path("tools");
        var names = new ArrayList<String>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        assertThat(names).contains("ZohoDesk_getTickets", "howto_refresh_tools");
    }

    @Test
    public void toolsList_withoutSession_isRejected() {
        Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list");
        // Unauthenticated MCP calls are rejected with 401, never a tool list.
        var ex = assertThrows(Exception.class, () ->
                httpClient.POST("/mcp")
                        .withRequestBody(request)
                        .responseBodyAs(String.class)
                        .invoke());
        assertThat(ex.getMessage()).contains("401");
    }
}
