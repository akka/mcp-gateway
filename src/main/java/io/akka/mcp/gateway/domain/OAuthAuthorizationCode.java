package io.akka.mcp.gateway.domain;

import java.time.Instant;

public record OAuthAuthorizationCode(
        String code,
        String clientId,
        String sessionToken,
        String redirectUri,
        String codeChallenge,
        String codeChallengeMethod,
        String scope,
        Instant expiresAt,
        boolean used) {

    public static OAuthAuthorizationCode empty() {
        return new OAuthAuthorizationCode(null, null, null, null, null, null, null, null, false);
    }

    public boolean isEmpty() {
        return code == null;
    }

    public boolean isExpired() {
        return expiresAt == null || Instant.now().isAfter(expiresAt);
    }

    public OAuthAuthorizationCode markUsed() {
        return new OAuthAuthorizationCode(code, clientId, sessionToken, redirectUri,
                codeChallenge, codeChallengeMethod, scope, expiresAt, true);
    }
}
