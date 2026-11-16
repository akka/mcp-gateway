package io.akka.mcp.gateway.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The client the gateway registered with a connector's authorization server, and the endpoints and scope found with
 * it. It is kept for the one resource it was registered for, and only for a short while, because the server may
 * forget the client without telling anyone.
 */
public record OAuthClientRegistration(String clientId, String authorizationEndpoint, String tokenEndpoint, String scope,
                                      String redirectUri, String resource, Instant registeredAt) {

    private static final Duration REUSE_FOR = Duration.ofHours(1);

    public boolean isReusableFor(String wantedRedirectUri, String wantedResource, Instant now) {
        return redirectUri.equals(wantedRedirectUri) && resource.equals(wantedResource) && now.isBefore(registeredAt.plus(REUSE_FOR));
    }
}
