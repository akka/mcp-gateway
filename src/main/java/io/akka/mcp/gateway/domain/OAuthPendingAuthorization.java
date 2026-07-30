package io.akka.mcp.gateway.domain;

import java.time.Instant;

public record OAuthPendingAuthorization(
        String clientId,
        String redirectUri,
        String codeChallenge,
        String codeChallengeMethod,
        String scope,
        String clientState,
        Instant expiresAt) {

    public static OAuthPendingAuthorization empty() {
        return new OAuthPendingAuthorization(null, null, null, null, null, null, null);
    }

    public boolean isEmpty() {
        return clientId == null;
    }

    public boolean isExpired() {
        return expiresAt == null || Instant.now().isAfter(expiresAt);
    }
}
