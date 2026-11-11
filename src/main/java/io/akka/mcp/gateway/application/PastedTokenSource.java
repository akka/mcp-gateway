package io.akka.mcp.gateway.application;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.client.KeyValueEntityClient;

import java.util.Optional;

/** The token a user pasted for one connector, kept in {@link TokenConnectionEntity}. */
public class PastedTokenSource implements BearerSource {

    private final ComponentClient componentClient;
    private final String connectorId;

    public PastedTokenSource(ComponentClient componentClient, String connectorId) {
        this.componentClient = componentClient;
        this.connectorId = connectorId;
    }

    @Override
    public boolean isConnected(String userId) {
        return entity(userId).method(TokenConnectionEntity::getStatus).invoke().connected();
    }

    @Override
    public Optional<String> bearerFor(String userId) {
        return entity(userId).method(TokenConnectionEntity::getToken).invoke();
    }

    @Override
    public boolean forgetIfRefused(String userId, String refusedBearer) {
        return entity(userId).method(TokenConnectionEntity::disconnectIfToken).invoke(refusedBearer);
    }

    /** Keeps a token the connector has already checked with the upstream. */
    public void keep(String userId, String token) {
        entity(userId).method(TokenConnectionEntity::connect).invoke(token);
    }

    @Override
    public void disconnect(String userId) {
        entity(userId).method(TokenConnectionEntity::disconnect).invoke();
    }

    private KeyValueEntityClient entity(String userId) {
        return componentClient.forKeyValueEntity(TokenConnectionEntity.keyFor(connectorId, userId));
    }
}
