package io.akka.mcp.gateway.domain;

import java.util.Optional;

/** What a connector has registered so far; nothing until the first sign-in. */
public record KeptOAuthRegistration(Optional<OAuthClientRegistration> registration) {

    public static KeptOAuthRegistration none() {
        return new KeptOAuthRegistration(Optional.empty());
    }
}
