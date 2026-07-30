package io.akka.mcp.gateway.domain;

import java.time.Instant;

public record GmailConnection(
        String accessToken,
        String refreshToken,
        Instant tokenExpiresAt,
        String pendingState,
        String codeVerifier,
        String clientId,
        String tokenEndpoint,
        Instant pendingExpiresAt
) {
    public static GmailConnection empty() {
        return new GmailConnection(null, null, null, null, null, null, null, null);
    }

    public boolean isConnected() { return accessToken != null; }

    public boolean isTokenExpired() {
        if (tokenExpiresAt == null) return false;
        return Instant.now().isAfter(tokenExpiresAt.minusSeconds(60));
    }

    public boolean isValidOAuthState(String candidate) {
        if (pendingState == null || pendingExpiresAt == null) return false;
        if (Instant.now().isAfter(pendingExpiresAt)) return false;
        return pendingState.equals(candidate);
    }

    public GmailConnection withPending(String state, String verifier, String dynClientId, String endpoint, Instant expiresAt) {
        return new GmailConnection(accessToken, refreshToken, tokenExpiresAt, state, verifier, dynClientId, endpoint, expiresAt);
    }

    public GmailConnection withToken(String token, String refresh, Instant expiresAt) {
        return new GmailConnection(token, refresh != null ? refresh : refreshToken, expiresAt, null, null, clientId, tokenEndpoint, null);
    }

    public GmailConnection disconnected() { return empty(); }
}
