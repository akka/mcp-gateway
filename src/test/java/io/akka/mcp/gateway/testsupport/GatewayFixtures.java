package io.akka.mcp.gateway.testsupport;

import akka.javasdk.client.ComponentClient;
import io.akka.mcp.gateway.application.McpAccessTokenEntity;
import io.akka.mcp.gateway.application.McpWritePolicyEntity;
import io.akka.mcp.gateway.application.SlackConnectionEntity;
import io.akka.mcp.gateway.application.UserSessionEntity;
import io.akka.mcp.gateway.domain.UserSession;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Setup shared by the integration tests: signed-in users, connections and the write policy. */
public final class GatewayFixtures {

    private GatewayFixtures() {}

    /** An MCP client's Bearer token, what {@code POST /mcp} accepts. */
    public static String mcpToken(ComponentClient client, String email, List<String> groups) {
        return mcpToken(client, email, groups, List.of());
    }

    public static String mcpToken(ComponentClient client, String email, List<String> groups, List<UserSession.App> apps) {
        var token = UUID.randomUUID().toString();
        client.forKeyValueEntity(token)
                .method(McpAccessTokenEntity::create)
                .invoke(new McpAccessTokenEntity.CreateCommand(
                        email, "User", groups, apps, "client-1", Instant.now().plusSeconds(3600)));
        return token;
    }

    /** A browser session cookie value, what the dashboard and admin routes accept. */
    public static String browserSession(ComponentClient client, String email, List<String> groups) {
        var token = UUID.randomUUID().toString();
        client.forKeyValueEntity(token)
                .method(UserSessionEntity::create)
                .invoke(new UserSessionEntity.CreateCommand(
                        email, "User", Instant.now().plusSeconds(3600), groups, "", List.of()));
        return token;
    }

    public static void connectSlack(ComponentClient client, String email) {
        client.forKeyValueEntity(email)
                .method(SlackConnectionEntity::initiatePkceOAuth)
                .invoke(new SlackConnectionEntity.InitiateCommand("state-1", "verifier-1", "client-id"));
        client.forKeyValueEntity(email)
                .method(SlackConnectionEntity::storeToken)
                .invoke(new SlackConnectionEntity.StoreTokenCommand(
                        "slack-access", "slack-refresh", Instant.now().plusSeconds(3600), "state-1"));
    }

    /** Replaces the write policy, whatever it was. */
    public static void enableWritesOn(ComponentClient client, List<String> mcpIds) {
        var current = client.forKeyValueEntity(McpWritePolicyEntity.ENTITY_ID)
                .method(McpWritePolicyEntity::get)
                .invoke();
        client.forKeyValueEntity(McpWritePolicyEntity.ENTITY_ID)
                .method(McpWritePolicyEntity::select)
                .invoke(new McpWritePolicyEntity.SelectCommand(mcpIds, current.version(), "test-setup"));
    }

    public static List<String> writeEnabledMcpIds(ComponentClient client) {
        return client.forKeyValueEntity(McpWritePolicyEntity.ENTITY_ID)
                .method(McpWritePolicyEntity::get)
                .invoke()
                .enabledMcpIds();
    }
}
