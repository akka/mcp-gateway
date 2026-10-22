package io.akka.mcp.gateway.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public record GoogleWorkspaceConnection(
        String accessToken,
        String refreshToken,
        Instant tokenExpiresAt,
        String pendingState,
        String codeVerifier,
        String clientId,
        String tokenEndpoint,
        Instant pendingExpiresAt,
        Set<String> grantedScopes
) {
    public enum ScopeGrant { GRANTED, MISSING, UNKNOWN }

    public static GoogleWorkspaceConnection empty() {
        return new GoogleWorkspaceConnection(null, null, null, null, null, null, null, null, null);
    }

    /** Parses the space-separated {@code scope} field of a Google token response. */
    public static Set<String> scopesFrom(String scopeField) {
        return Arrays.stream(scopeField.trim().split("\\s+"))
                .filter(scope -> !scope.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** {@code UNKNOWN} when the connection was stored before granted scopes were recorded. */
    public ScopeGrant scopeGrant(String scope) {
        if (grantedScopes == null) return ScopeGrant.UNKNOWN;
        return grantedScopes.contains(scope) ? ScopeGrant.GRANTED : ScopeGrant.MISSING;
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

    public GoogleWorkspaceConnection withPending(String state, String verifier, String dynClientId, String endpoint, Instant expiresAt) {
        return new GoogleWorkspaceConnection(accessToken, refreshToken, tokenExpiresAt, state, verifier, dynClientId, endpoint, expiresAt, grantedScopes);
    }

    public GoogleWorkspaceConnection withToken(String token, String refresh, Instant expiresAt) {
        return new GoogleWorkspaceConnection(token, refresh, expiresAt, null, null, clientId, tokenEndpoint, null, grantedScopes);
    }

    public GoogleWorkspaceConnection withToken(String token, String refresh, Instant expiresAt, Set<String> scopes) {
        return new GoogleWorkspaceConnection(token, refresh, expiresAt, null, null, clientId, tokenEndpoint, null, scopes);
    }

    public GoogleWorkspaceConnection withGrantedScopes(Set<String> scopes) {
        return new GoogleWorkspaceConnection(accessToken, refreshToken, tokenExpiresAt, pendingState, codeVerifier,
                clientId, tokenEndpoint, pendingExpiresAt, scopes);
    }

    public GoogleWorkspaceConnection disconnected() {
        return empty();
    }
}
