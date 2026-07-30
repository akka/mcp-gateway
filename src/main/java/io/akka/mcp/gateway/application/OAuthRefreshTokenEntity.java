package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.mcp.gateway.domain.OAuthRefreshToken;

import java.time.Instant;
import java.util.List;

@Component(id = "oauth-refresh-token")
public class OAuthRefreshTokenEntity extends KeyValueEntity<OAuthRefreshToken> {

    @Override
    public OAuthRefreshToken emptyState() {
        return OAuthRefreshToken.empty();
    }

    public Effect<Done> create(CreateCommand cmd) {
        var state = new OAuthRefreshToken(
                cmd.token(), cmd.userId(), cmd.displayName(),
                cmd.clientId(), cmd.groups(), cmd.expiresAt(), false);
        return effects().updateState(state).thenReply(Done.getInstance());
    }

    public ReadOnlyEffect<OAuthRefreshToken> get() {
        return effects().reply(currentState());
    }

    public Effect<Done> revoke() {
        if (currentState().isEmpty()) return effects().reply(Done.getInstance());
        var revoked = new OAuthRefreshToken(
                currentState().token(), currentState().userId(), currentState().displayName(),
                currentState().clientId(), currentState().groups(), currentState().expiresAt(), true);
        return effects().updateState(revoked).thenReply(Done.getInstance());
    }

    public record CreateCommand(
            String token,
            String userId,
            String displayName,
            String clientId,
            List<String> groups,
            Instant expiresAt) {}
}
