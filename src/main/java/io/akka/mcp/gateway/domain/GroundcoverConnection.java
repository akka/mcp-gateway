package io.akka.mcp.gateway.domain;

import java.time.Instant;

public record GroundcoverConnection(
        String accessToken,
        String refreshToken,
        Instant tokenExpiresAt,
        String pendingState,
        String codeVerifier,
        String clientId,
        String tokenEndpoint,
        Instant pendingExpiresAt
) {
    public static GroundcoverConnection empty() {
        return new GroundcoverConnection(null, null, null, null, null, null, null, null);
    }

    public boolean isConnected() {
        return accessToken != null;
    }

    public boolean isValidPendingState(String candidate) {
        if (pendingState == null || pendingExpiresAt == null) return false;
        if (Instant.now().isAfter(pendingExpiresAt)) return false;
        return pendingState.equals(candidate);
    }

    public GroundcoverConnection withPending(String state, String verifier, String dynClientId, String endpoint, Instant expiresAt) {
        return new GroundcoverConnection(accessToken, refreshToken, tokenExpiresAt, state, verifier, dynClientId, endpoint, expiresAt);
    }

    public boolean isTokenExpired() {
        if (tokenExpiresAt == null) return false;
        return Instant.now().isAfter(tokenExpiresAt.minusSeconds(60));
    }

    public GroundcoverConnection withToken(String token, String refresh, Instant expiresAt) {
        return new GroundcoverConnection(token, refresh, expiresAt, null, null, clientId, tokenEndpoint, null);
    }

    public GroundcoverConnection disconnected() {
        return empty();
    }
}
