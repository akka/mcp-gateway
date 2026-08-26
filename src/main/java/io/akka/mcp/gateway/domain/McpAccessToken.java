package io.akka.mcp.gateway.domain;

import java.time.Instant;
import java.util.List;

/**
 * A token issued to an MCP client (e.g. Claude Code) via the {@code /oauth2/token} endpoint.
 *
 * Deliberately a distinct type and value space from {@link UserSession}: earlier, the OAuth
 * access token issued to MCP clients was the user's live browser session token, so anyone who
 * obtained it (e.g. via an attacker-controlled {@code redirect_uri}) held a working {@code
 * SESSION} cookie too, including admin dashboard access. An {@code McpAccessToken} carries the
 * same authorization claims but is scoped to the MCP JSON-RPC endpoint only (see
 * {@code AbstractProtectedEndpoint#requireMcpSession()}) and cannot be replayed as a browser
 * session.
 */
public record McpAccessToken(
        String userId,
        String displayName,
        List<String> groups,
        List<UserSession.App> apps,
        String clientId,
        Instant expiresAt
) {
    public static McpAccessToken empty() {
        return new McpAccessToken(null, null, List.of(), List.of(), null, null);
    }

    public boolean isEmpty() {
        return userId == null;
    }

    public boolean isExpired() {
        return expiresAt == null || Instant.now().isAfter(expiresAt);
    }

    public List<String> groups() {
        return groups != null ? groups : List.of();
    }

    public List<UserSession.App> apps() {
        return apps != null ? apps : List.of();
    }

    /** Projects this token's claims onto a {@link UserSession} so existing authorization checks
     * ({@code hasRole}, {@code canInteract}, {@code isAdmin}, {@code hasApp}) work unchanged. This
     * is not a real session and is never stored as one — it exists only in memory for the
     * duration of a request. */
    public UserSession asUserSession() {
        return new UserSession(userId, displayName, null, expiresAt, groups(), null, apps());
    }
}
