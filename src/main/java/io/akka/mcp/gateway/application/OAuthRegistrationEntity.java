package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.mcp.gateway.domain.KeptOAuthRegistration;
import io.akka.mcp.gateway.domain.OAuthClientRegistration;

import java.util.Optional;

/** Keyed by the connector id: one registered client is shared by every user, so a sign-in does not register a new one. */
@Component(id = "oauth-client-registration")
public class OAuthRegistrationEntity extends KeyValueEntity<KeptOAuthRegistration> {

    @Override
    public KeptOAuthRegistration emptyState() {
        return KeptOAuthRegistration.none();
    }

    public ReadOnlyEffect<KeptOAuthRegistration> get() {
        return effects().reply(currentState());
    }

    /** Drops the registration, so the next sign-in registers again; the authorization server may have forgotten the client. */
    public Effect<Done> forget() {
        return effects().updateState(KeptOAuthRegistration.none()).thenReply(Done.getInstance());
    }

    public Effect<Done> keep(OAuthClientRegistration registration) {
        return effects().updateState(new KeptOAuthRegistration(Optional.of(registration))).thenReply(Done.getInstance());
    }
}
