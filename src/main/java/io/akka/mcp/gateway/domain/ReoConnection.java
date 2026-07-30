package io.akka.mcp.gateway.domain;

import java.time.Instant;

public record ReoConnection(
        String accessToken,
        String refreshToken,
        Instant tokenExpiresAt,
        String pendingState,
        String codeVerifier,
        String clientId,
        String tokenEndpoint,
        Instant pendingExpiresAt
) {
    public static ReoConnection empty() {
        return new ReoConnection(null, null, null, null, null, null, null, null);
    }

    public boolean isConnected() {
        return accessToken != null;
    }

    public boolean isValidPendingState(String candidate) {
        if (pendingState == null || pendingExpiresAt == null) return false;
        if (Instant.now().isAfter(pendingExpiresAt)) return false;
        return pendingState.equals(candidate);
    }

    public ReoConnection withPending(String state, String verifier, String dynClientId, String endpoint, Instant expiresAt) {
        return new ReoConnection(accessToken, refreshToken, tokenExpiresAt, state, verifier, dynClientId, endpoint, expiresAt);
    }

    public boolean isTokenExpired() {
        if (tokenExpiresAt == null) return false;
        return Instant.now().isAfter(tokenExpiresAt.minusSeconds(60));
    }

    public ReoConnection withToken(String token, String refresh, Instant expiresAt) {
        return new ReoConnection(token, refresh, expiresAt, null, null, clientId, tokenEndpoint, null);
    }

    public ReoConnection disconnected() {
        return empty();
    }
}
