package io.akka.mcp.gateway.domain;

import java.time.Instant;
import java.util.List;

public record OAuthRefreshToken(
        String token,
        String userId,
        String displayName,
        String clientId,
        List<String> groups,
        Instant expiresAt,
        boolean revoked,
        List<UserSession.App> apps,
        String idToken
) {
    public static OAuthRefreshToken empty() {
        return new OAuthRefreshToken(null, null, null, null, List.of(), null, false, List.of(), null);
    }

    public List<UserSession.App> apps() {
        return apps != null ? apps : List.of();
    }

    public boolean isEmpty()  { return token == null; }
    public boolean isExpired() { return expiresAt == null || Instant.now().isAfter(expiresAt); }
    public boolean isValid()  { return !isEmpty() && !isExpired() && !revoked; }
}
