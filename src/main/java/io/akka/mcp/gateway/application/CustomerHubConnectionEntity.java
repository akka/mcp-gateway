package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.mcp.gateway.domain.CustomerHubConnection;

import java.util.Optional;

/** Keyed by the user's email, like the other per-user connections. */
@Component(id = "customer-hub-connection")
public class CustomerHubConnectionEntity extends KeyValueEntity<CustomerHubConnection> {

    public record Status(boolean connected) {}

    @Override
    public CustomerHubConnection emptyState() {
        return CustomerHubConnection.empty();
    }

    public ReadOnlyEffect<Status> getStatus() {
        return effects().reply(new Status(currentState().isConnected()));
    }

    public ReadOnlyEffect<Optional<String>> getToken() {
        return effects().reply(currentState().token());
    }

    public Effect<Done> connect(String token) {
        if (!CustomerHubConnection.isWellFormedToken(token)) {
            return effects().error("Not a usable Customer Hub token.");
        }
        return effects().updateState(currentState().withToken(token)).thenReply(Done.getInstance());
    }

    public Effect<Done> disconnect() {
        return effects().updateState(currentState().disconnected()).thenReply(Done.getInstance());
    }

    /** Forgets the token only if it is still the one the hub refused, so a token pasted since is kept. Replies whether it was forgotten. */
    public Effect<Boolean> disconnectIfToken(String refused) {
        if (currentState().token().filter(refused::equals).isEmpty()) return effects().reply(false);
        return effects().updateState(currentState().disconnected()).thenReply(true);
    }
}
