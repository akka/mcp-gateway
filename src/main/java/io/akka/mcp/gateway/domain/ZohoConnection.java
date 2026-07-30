package io.akka.mcp.gateway.domain;

import java.time.Instant;

public record ZohoConnection(
        String accessToken,
        String refreshToken,
        Instant tokenExpiresAt,
        String pendingState,
        String codeVerifier,
        String clientId,
        String tokenEndpoint,
        Instant pendingExpiresAt
) {
    public static ZohoConnection empty() {
        return new ZohoConnection(null, null, null, null, null, null, null, null);
    }

    public boolean isConnected() {
        return accessToken != null;
    }

    public boolean isValidPendingState(String candidate) {
        if (pendingState == null || pendingExpiresAt == null) return false;
        if (Instant.now().isAfter(pendingExpiresAt)) return false;
        return pendingState.equals(candidate);
    }

    public ZohoConnection withPending(String state, String verifier, String dynClientId, String endpoint, Instant expiresAt) {
        return new ZohoConnection(accessToken, refreshToken, tokenExpiresAt, state, verifier, dynClientId, endpoint, expiresAt);
    }

    public boolean isTokenExpired() {
        if (tokenExpiresAt == null) return false;
        return Instant.now().isAfter(tokenExpiresAt.minusSeconds(60));
    }

    public ZohoConnection withToken(String token, String refresh, Instant expiresAt) {
        // preserve clientId and tokenEndpoint — needed for future token refresh calls
        return new ZohoConnection(token, refresh, expiresAt, null, null, clientId, tokenEndpoint, null);
    }

    public ZohoConnection disconnected() {
        return empty();
    }
}
