package io.akka.mcp.gateway.domain;

import java.time.Instant;

public record OidcPendingLogin(
        String loginHint,
        Instant expiresAt,
        String codeVerifier
) {
    public static OidcPendingLogin empty() {
        return new OidcPendingLogin(null, null, null);
    }

    public boolean isEmpty() {
        return loginHint == null;
    }

    public boolean isExpired() {
        if (expiresAt == null) return true;
        return Instant.now().isAfter(expiresAt);
    }
}
