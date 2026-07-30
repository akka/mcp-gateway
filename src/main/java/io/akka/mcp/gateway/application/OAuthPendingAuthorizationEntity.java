package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.mcp.gateway.domain.OAuthPendingAuthorization;

import java.time.Instant;

@Component(id = "oauth-pending-auth")
public class OAuthPendingAuthorizationEntity extends KeyValueEntity<OAuthPendingAuthorization> {

    @Override
    public OAuthPendingAuthorization emptyState() {
        return OAuthPendingAuthorization.empty();
    }

    public Effect<Done> create(CreateCommand cmd) {
        return effects()
                .updateState(new OAuthPendingAuthorization(
                        cmd.clientId(), cmd.redirectUri(), cmd.codeChallenge(),
                        cmd.codeChallengeMethod(), cmd.scope(), cmd.clientState(), cmd.expiresAt()))
                .thenReply(Done.getInstance());
    }

    public ReadOnlyEffect<OAuthPendingAuthorization> get() {
        return effects().reply(currentState());
    }

    public Effect<Done> delete() {
        return effects().updateState(OAuthPendingAuthorization.empty()).thenReply(Done.getInstance());
    }

    public record CreateCommand(
            String clientId,
            String redirectUri,
            String codeChallenge,
            String codeChallengeMethod,
            String scope,
            String clientState,
            Instant expiresAt) {}
}
