package io.akka.mcp.gateway.domain;

import java.time.Instant;

public record SalesforceConnection(
        String accessToken,
        String refreshToken,
        Instant tokenExpiresAt,
        String pendingState,
        String codeVerifier,
        String clientId,
        String tokenEndpoint,
        Instant pendingExpiresAt
) {
    public static SalesforceConnection empty() {
        return new SalesforceConnection(null, null, null, null, null, null, null, null);
    }

    public boolean isConnected() {
        return accessToken != null;
    }

    public boolean isTokenExpired() {
        if (tokenExpiresAt == null) return false;
        return Instant.now().isAfter(tokenExpiresAt.minusSeconds(60));
    }

    public boolean isValidOAuthState(String candidate) {
        if (pendingState == null || pendingExpiresAt == null) return false;
        if (Instant.now().isAfter(pendingExpiresAt)) return false;
        return pendingState.equals(candidate);
    }

    public SalesforceConnection withPending(String state, String verifier, String dynClientId, String endpoint, Instant expiresAt) {
        return new SalesforceConnection(accessToken, refreshToken, tokenExpiresAt, state, verifier, dynClientId, endpoint, expiresAt);
    }

    public SalesforceConnection withToken(String token, String refresh, Instant expiresAt) {
        return new SalesforceConnection(token, refresh != null ? refresh : refreshToken, expiresAt, null, null, clientId, tokenEndpoint, null);
    }

    public SalesforceConnection disconnected() {
        return empty();
    }
}
