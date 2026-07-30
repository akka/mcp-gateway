package io.akka.mcp.gateway.domain;

import java.time.Instant;

public record GoogleDriveConnection(
        String accessToken,
        String refreshToken,
        Instant tokenExpiresAt,
        String pendingState,
        String codeVerifier,
        String clientId,
        String tokenEndpoint,
        Instant pendingExpiresAt
) {
    public static GoogleDriveConnection empty() {
        return new GoogleDriveConnection(null, null, null, null, null, null, null, null);
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

    public GoogleDriveConnection withPending(String state, String verifier, String dynClientId, String endpoint, Instant expiresAt) {
        return new GoogleDriveConnection(accessToken, refreshToken, tokenExpiresAt, state, verifier, dynClientId, endpoint, expiresAt);
    }

    public GoogleDriveConnection withToken(String token, String refresh, Instant expiresAt) {
        return new GoogleDriveConnection(token, refresh, expiresAt, null, null, clientId, tokenEndpoint, null);
    }

    public GoogleDriveConnection disconnected() {
        return empty();
    }
}
