package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.mcp.gateway.domain.OidcPendingLogin;

import java.time.Instant;

@Component(id = "oidc-pending-login")
public class OidcPendingLoginEntity extends KeyValueEntity<OidcPendingLogin> {

    @Override
    public OidcPendingLogin emptyState() {
        return OidcPendingLogin.empty();
    }

    public Effect<Done> create(CreateCommand cmd) {
        return effects()
                .updateState(new OidcPendingLogin(cmd.loginHint(), cmd.expiresAt(), cmd.codeVerifier()))
                .thenReply(Done.getInstance());
    }

    public ReadOnlyEffect<OidcPendingLogin> get() {
        return effects().reply(currentState());
    }

    public Effect<Done> delete() {
        return effects().updateState(OidcPendingLogin.empty()).thenReply(Done.getInstance());
    }

    public record CreateCommand(String loginHint, Instant expiresAt, String codeVerifier) {}
}
