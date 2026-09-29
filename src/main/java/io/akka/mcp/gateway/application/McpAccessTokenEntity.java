package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.mcp.gateway.domain.McpAccessToken;
import io.akka.mcp.gateway.domain.UserSession;

import java.time.Instant;
import java.util.List;

/**
 * Tokens issued to MCP clients (e.g. Claude Code) at {@code /oauth2/token}. Kept as a separate
 * component from {@link UserSessionEntity} on purpose — see {@link McpAccessToken} — so a token
 * handed to an MCP client is never interchangeable with the user's browser {@code SESSION}
 * cookie.
 */
@Component(id = "mcp-access-token")
public class McpAccessTokenEntity extends KeyValueEntity<McpAccessToken> {

    @Override
    public McpAccessToken emptyState() {
        return McpAccessToken.empty();
    }

    public Effect<Done> create(CreateCommand cmd) {
        var state = new McpAccessToken(
                cmd.userId(), cmd.displayName(), cmd.groups(), cmd.apps(), cmd.clientId(), cmd.expiresAt());
        return effects().updateState(state).thenReply(Done.getInstance());
    }

    public ReadOnlyEffect<McpAccessToken> get() {
        return effects().reply(currentState());
    }

    public Effect<Done> revoke() {
        return effects().updateState(McpAccessToken.empty()).thenReply(Done.getInstance());
    }

    public record CreateCommand(
            String userId,
            String displayName,
            List<String> groups,
            List<UserSession.App> apps,
            String clientId,
            Instant expiresAt) {}
}
