package io.akka.mcp.gateway.domain;

import java.time.Instant;

public record HubspotConnection(
        String accessToken,
        String refreshToken,
        Instant tokenExpiresAt,
        String pendingState,
        String codeVerifier,
        String clientId,
        Instant pendingExpiresAt
) {
    public static HubspotConnection empty() {
        return new HubspotConnection(null, null, null, null, null, null, null);
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

    public HubspotConnection withPending(String state, String verifier, String dynClientId, Instant expiresAt) {
        return new HubspotConnection(accessToken, refreshToken, tokenExpiresAt, state, verifier, dynClientId, expiresAt);
    }

    public HubspotConnection withToken(String token, String refresh, Instant expiresAt) {
        return new HubspotConnection(token, refresh != null ? refresh : refreshToken, expiresAt, null, null, clientId, null);
    }

    public HubspotConnection disconnected() { return empty(); }
}
