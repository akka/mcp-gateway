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
        boolean revoked
) {
    public static OAuthRefreshToken empty() {
        return new OAuthRefreshToken(null, null, null, null, List.of(), null, false);
    }

    public boolean isEmpty()  { return token == null; }
    public boolean isExpired() { return expiresAt == null || Instant.now().isAfter(expiresAt); }
    public boolean isValid()  { return !isEmpty() && !isExpired() && !revoked; }
}
