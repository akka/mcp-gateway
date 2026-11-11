package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.mcp.gateway.domain.TokenConnection;

import java.util.Optional;

/** Keyed by the connector id and the user's email, because one user can paste a token for several connectors. */
@Component(id = "token-connection")
public class TokenConnectionEntity extends KeyValueEntity<TokenConnection> {

    public record Status(boolean connected) {}

    /** A connector id has no colon, so the first one always ends it. */
    public static String keyFor(String connectorId, String userId) {
        return connectorId + ":" + userId;
    }

    @Override
    public TokenConnection emptyState() {
        return TokenConnection.empty();
    }

    public ReadOnlyEffect<Status> getStatus() {
        return effects().reply(new Status(currentState().isConnected()));
    }

    public ReadOnlyEffect<Optional<String>> getToken() {
        return effects().reply(currentState().token());
    }

    public Effect<Done> connect(String token) {
        if (!TokenConnection.isWellFormedToken(token)) {
            return effects().error("Not a usable token.");
        }
        return effects().updateState(currentState().withToken(token)).thenReply(Done.getInstance());
    }

    public Effect<Done> disconnect() {
        return effects().updateState(currentState().disconnected()).thenReply(Done.getInstance());
    }

    /** Forgets the token only if it is still the one the upstream refused, so a token pasted since is kept. Replies whether it was forgotten. */
    public Effect<Boolean> disconnectIfToken(String refused) {
        if (currentState().token().filter(refused::equals).isEmpty()) return effects().reply(false);
        return effects().updateState(currentState().disconnected()).thenReply(true);
    }
}
