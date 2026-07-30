package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Location;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.UserSessionEntity;
import io.akka.mcp.gateway.domain.UserSession;

/**
 * Base class for endpoints that require an authenticated user session.
 *
 * Session identity is resolved from two sources, in priority order:
 *   1. {@code Authorization: Bearer <token>} header — used by MCP clients
 *   2. {@code SESSION} cookie — used by the browser UI
 *
 * Subclasses call {@link #requireSession()} to gate access. A {@code null} return means
 * no valid session exists and the handler should return one of the pre-built responses:
 * {@link #redirectToLogin()} for browser flows, {@link #unauthorized()} for API flows,
 * or {@link #unauthorizedForMcp()} for MCP clients (adds the RFC 9728 WWW-Authenticate header
 * that tells the MCP client where to start the OAuth 2.1 flow).
 */
public abstract class AbstractProtectedEndpoint extends AbstractHttpEndpoint {

    protected final ComponentClient componentClient;
    protected final String mcpBaseUrl;
    protected final String readerGroup;
    protected final String writerGroup;
    protected final String adminGroup;
    protected final String escalaterGroup;

    protected AbstractProtectedEndpoint(ComponentClient componentClient, Config config) {
        this.componentClient = componentClient;
        this.mcpBaseUrl = config.getString("mcp.base-url");
        this.readerGroup = config.getString("okta.groups.reader");
        this.writerGroup = config.getString("okta.groups.writer");
        this.adminGroup = config.getString("okta.groups.admin");
        this.escalaterGroup = config.getString("okta.groups.escalater");
    }

    protected UserSession requireSession() {
        String sessionToken = getSessionToken();
        if (sessionToken == null || sessionToken.isBlank()) return null;

        var session = componentClient
                .forKeyValueEntity(sessionToken)
                .method(UserSessionEntity::getSession)
                .invoke();

        if (session.isEmpty() || session.isExpired()) return null;
        return session;
    }

    protected String getSessionToken() {
        // Bearer token takes precedence over cookie (used by MCP clients)
        var authHeader = requestContext().requestHeader("authorization").map(HttpHeader::value).orElse(null);
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authHeader.substring(7).trim();
            if (!token.isBlank()) return token;
        }
        String cookieHeader = requestContext()
                .requestHeader("Cookie")
                .map(HttpHeader::value)
                .orElse("");
        return parseCookie(cookieHeader, "SESSION");
    }

    protected HttpResponse requireGroup(UserSession session, String group) {
        if (!session.hasRole(group)) return forbidden();
        return null;
    }

    protected HttpResponse requireAdmin(UserSession session) {
        if (!session.isAdmin(adminGroup)) return forbidden();
        return null;
    }

    protected HttpResponse forbidden() {
        return HttpResponse.create()
                .withStatus(StatusCodes.FORBIDDEN)
                .withEntity(ContentTypes.TEXT_PLAIN_UTF8, "Access denied");
    }

    protected HttpResponse redirectToLogin() {
        return HttpResponse.create()
                .withStatus(StatusCodes.FOUND)
                .addHeader(Location.create("/login"));
    }

    protected HttpResponse unauthorized() {
        return HttpResponse.create()
                .withStatus(StatusCodes.UNAUTHORIZED)
                .withEntity(ContentTypes.TEXT_PLAIN_UTF8, "Not authenticated");
    }

    protected HttpResponse unauthorizedForMcp() {
        String prmUrl = mcpBaseUrl + "/.well-known/oauth-protected-resource";
        return HttpResponse.create()
                .withStatus(StatusCodes.UNAUTHORIZED)
                .addHeader(RawHeader.create("WWW-Authenticate",
                        "Bearer realm=\"mcp\", resource_metadata=\"" + prmUrl + "\""))
                .withEntity(ContentTypes.APPLICATION_JSON,
                        "{\"error\":\"invalid_token\",\"error_description\":\"Authentication required\"}");
    }

    protected HttpResponse redirectTo(String path) {
        return HttpResponse.create()
                .withStatus(StatusCodes.FOUND)
                .addHeader(Location.create(path));
    }

    protected static String parseCookie(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.isBlank()) return null;
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1).trim();
            }
        }
        return null;
    }
}
