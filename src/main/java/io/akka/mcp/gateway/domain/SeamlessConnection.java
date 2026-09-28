package io.akka.mcp.gateway.domain;

import java.time.Instant;

public record SeamlessConnection(
        String accessToken,
        String refreshToken,
        Instant tokenExpiresAt,
        String pendingState,
        String codeVerifier,
        String clientId,
        String tokenEndpoint,
        Instant pendingExpiresAt
) {
    public static SeamlessConnection empty() {
        return new SeamlessConnection(null, null, null, null, null, null, null, null);
    }

    public boolean isConnected() {
        return accessToken != null;
    }

    public boolean isValidPendingState(String candidate) {
        if (pendingState == null || pendingExpiresAt == null) return false;
        if (Instant.now().isAfter(pendingExpiresAt)) return false;
        return pendingState.equals(candidate);
    }

    public SeamlessConnection withPending(String state, String verifier, String dynClientId, String endpoint, Instant expiresAt) {
        return new SeamlessConnection(accessToken, refreshToken, tokenExpiresAt, state, verifier, dynClientId, endpoint, expiresAt);
    }

    public boolean isTokenExpired() {
        if (tokenExpiresAt == null) return false;
        return Instant.now().isAfter(tokenExpiresAt.minusSeconds(60));
    }

    public SeamlessConnection withToken(String token, String refresh, Instant expiresAt) {
        return new SeamlessConnection(token, refresh, expiresAt, null, null, clientId, tokenEndpoint, null);
    }

    public SeamlessConnection disconnected() {
        return empty();
    }
}
