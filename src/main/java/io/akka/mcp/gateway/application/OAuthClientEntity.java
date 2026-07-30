package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.mcp.gateway.domain.OAuthClient;

import java.time.Instant;

@Component(id = "oauth-client")
public class OAuthClientEntity extends KeyValueEntity<OAuthClient> {

    @Override
    public OAuthClient emptyState() {
        return OAuthClient.empty();
    }

    public Effect<Done> register(RegisterCommand cmd) {
        return effects()
                .updateState(new OAuthClient(cmd.clientId(), cmd.clientName(), cmd.redirectUri(), Instant.now()))
                .thenReply(Done.getInstance());
    }

    public ReadOnlyEffect<OAuthClient> get() {
        return effects().reply(currentState());
    }

    public record RegisterCommand(String clientId, String clientName, String redirectUri) {}
}
