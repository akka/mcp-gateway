package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.mcp.gateway.domain.OAuthAuthorizationCode;

import java.time.Instant;

@Component(id = "oauth-auth-code")
public class OAuthAuthorizationCodeEntity extends KeyValueEntity<OAuthAuthorizationCode> {

    @Override
    public OAuthAuthorizationCode emptyState() {
        return OAuthAuthorizationCode.empty();
    }

    public Effect<Done> create(CreateCommand cmd) {
        return effects()
                .updateState(new OAuthAuthorizationCode(
                        cmd.code(), cmd.clientId(), cmd.sessionToken(),
                        cmd.redirectUri(), cmd.codeChallenge(), cmd.codeChallengeMethod(),
                        cmd.scope(), cmd.expiresAt(), false))
                .thenReply(Done.getInstance());
    }

    public ReadOnlyEffect<OAuthAuthorizationCode> get() {
        return effects().reply(currentState());
    }

    public Effect<Done> markUsed() {
        return effects()
                .updateState(currentState().markUsed())
                .thenReply(Done.getInstance());
    }

    public record CreateCommand(
            String code,
            String clientId,
            String sessionToken,
            String redirectUri,
            String codeChallenge,
            String codeChallengeMethod,
            String scope,
            Instant expiresAt) {}
}
