package io.akka.mcp.gateway.domain;

import java.time.Instant;

public record OidcPendingLogin(
        String loginHint,
        Instant expiresAt,
        String codeVerifier,
        String returnTo
) {
    public static final String DASHBOARD = "/";

    public static OidcPendingLogin empty() {
        return new OidcPendingLogin(null, null, null, null);
    }

    public boolean isEmpty() {
        return loginHint == null;
    }

    public boolean isExpired() {
        if (expiresAt == null) return true;
        return Instant.now().isAfter(expiresAt);
    }

    /** Where the browser goes after sign-in. A row stored without a return path lands on the dashboard. */
    public String landingPath() {
        return returnTo == null ? DASHBOARD : returnTo;
    }
}
