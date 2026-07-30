package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Location;
import java.util.UUID;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.McpInteractionEntity;
import io.akka.mcp.gateway.application.RemoteMcpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Base class for MCP OAuth connections that use a pre-registered (static) client.
 * The client_id and OAuth endpoints are known upfront from configuration — there is
 * no discovery phase and no Dynamic Client Registration. The connect flow goes straight
 * to PKCE setup and redirects the user to the provider's fixed authorization endpoint.
 *
 * @see AbstractDcrOAuthEndpoint for providers where the client must register dynamically.
 */
public abstract class AbstractStaticOAuthEndpoint extends AbstractMcpConnectionEndpoint {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected AbstractStaticOAuthEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
    }

    protected abstract String getClientId();
    protected abstract String getRedirectUri();
    protected abstract String getClientSecret();
    protected abstract String getAuthorizationEndpoint();
    protected abstract String getTokenEndpoint();
    protected abstract String getScope();
    protected abstract void storePendingOAuth(String email, String state, String codeVerifier, String clientId, String tokenEndpoint);
    protected abstract Optional<PendingOAuthState> validatePendingState(String email, String state);
    protected abstract void storeToken(String email, String accessToken, String refreshToken, Instant expiresAt, String state);
    protected abstract void clearConnection(String email);
    protected abstract Optional<String> fetchAccessToken(String email);
    protected abstract String getProviderLabel();
    protected abstract RemoteMcpClient createMcpClient();

    protected String getExtraAuthParams() { return ""; }

    protected String extractAccessToken(com.fasterxml.jackson.databind.JsonNode tokenJson) {
        return tokenJson.path("access_token").asText();
    }

    protected String extractRefreshToken(com.fasterxml.jackson.databind.JsonNode tokenJson) {
        return tokenJson.path("refresh_token").asText(null);
    }

    protected long getDefaultTokenExpiry() { return 3600; }

    protected void recordConnectionEvent(String email, String direction, String detail) {
        componentClient
                .forEventSourcedEntity(UUID.randomUUID().toString())
                .method(McpInteractionEntity::record)
                .invoke(new McpInteractionEntity.RecordCommand(
                        email, getProviderLabel().toLowerCase(), "oauth", Map.of(), direction, detail));
    }

    public HttpResponse callback() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        String code = requestContext().queryParams().getString("code").orElse(null);
        String state = requestContext().queryParams().getString("state").orElse(null);

        if (code == null || state == null) {
            recordConnectionEvent(session.email(), "connect-failed", "Missing code or state in callback");
            return HttpResponses.badRequest("Missing required parameters");
        }

        var pending = validatePendingState(session.email(), state);
        if (pending.isEmpty()) {
            recordConnectionEvent(session.email(), "connect-failed", "Invalid or expired OAuth state");
            return HttpResponses.badRequest("Invalid or expired OAuth state");
        }
        var p = pending.get();

        try {
            String formBody = "grant_type=authorization_code"
                    + "&code=" + encode(code)
                    + "&client_id=" + encode(p.clientId())
                    + "&redirect_uri=" + encode(getRedirectUri())
                    + "&code_verifier=" + encode(p.codeVerifier())
                    + (getClientSecret().isBlank() ? "" : "&client_secret=" + encode(getClientSecret()));

            var httpClient = HttpClient.newHttpClient();
            var tokenResp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(p.tokenEndpoint()))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(formBody))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (tokenResp.statusCode() != 200) {
                recordConnectionEvent(session.email(), "connect-failed",
                        "Token exchange HTTP " + tokenResp.statusCode() + ": " + tokenResp.body());
                return HttpResponse.create()
                        .withStatus(StatusCodes.BAD_GATEWAY)
                        .withEntity(akka.http.javadsl.model.ContentTypes.TEXT_PLAIN_UTF8,
                                "Token exchange failed: " + tokenResp.body());
            }

            var tokenJson = MAPPER.readTree(tokenResp.body());
            String accessToken = extractAccessToken(tokenJson);
            String refreshToken = extractRefreshToken(tokenJson);
            long expiresIn = tokenJson.path("expires_in").asLong(getDefaultTokenExpiry());
            Instant expiresAt = Instant.now().plusSeconds(expiresIn);

            storeToken(session.email(), accessToken, refreshToken, expiresAt, state);
            recordConnectionEvent(session.email(), "connect-success", null);
            warmRegistryCache(createMcpClient(), session.email());

        } catch (Exception e) {
            recordConnectionEvent(session.email(), "connect-failed", "Token exchange exception: " + e.getMessage());
            return HttpResponse.create()
                    .withStatus(StatusCodes.BAD_GATEWAY)
                    .withEntity(akka.http.javadsl.model.ContentTypes.TEXT_PLAIN_UTF8,
                            "Token exchange failed: " + e.getMessage());
        }

        return HttpResponse.create()
                .withStatus(StatusCodes.FOUND)
                .addHeader(Location.create("/"));
    }

    public HttpResponse connect() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        if (getClientId().isBlank()) {
            return HttpResponses.internalServerError(getProviderLabel() + " client ID is not configured.");
        }
        if (getRedirectUri().isBlank()) {
            return HttpResponses.internalServerError(getProviderLabel() + " redirect URI is not configured.");
        }
        recordConnectionEvent(session.email(), "connect-attempt", null);
        try {
            String codeVerifier = generateCodeVerifier();
            String codeChallenge = generateCodeChallenge(codeVerifier);
            String state = UUID.randomUUID().toString();

            storePendingOAuth(session.email(), state, codeVerifier, getClientId(), getTokenEndpoint());

            String authorizeUrl = getAuthorizationEndpoint()
                    + "?response_type=code"
                    + "&client_id=" + encode(getClientId())
                    + "&redirect_uri=" + encode(getRedirectUri())
                    + "&state=" + encode(state)
                    + "&code_challenge=" + encode(codeChallenge)
                    + "&code_challenge_method=S256"
                    + "&scope=" + encode(getScope())
                    + getExtraAuthParams();

            return HttpResponse.create()
                    .withStatus(StatusCodes.FOUND)
                    .addHeader(Location.create(authorizeUrl));
        } catch (Exception e) {
            recordConnectionEvent(session.email(), "connect-failed", "OAuth setup failed: " + e.getMessage());
            return HttpResponse.create()
                    .withStatus(StatusCodes.INTERNAL_SERVER_ERROR)
                    .withEntity(akka.http.javadsl.model.ContentTypes.TEXT_PLAIN_UTF8,
                            "OAuth setup failed: " + e.getMessage());
        }
    }

    public HttpResponse test() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        log.info("[{}] Test Connection requested by {}", getProviderLabel(), session.email());
        return HttpResponses.ok(testMcpClient(createMcpClient(), session.email(), getProviderLabel()));
    }

    public HttpResponse token() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        return fetchAccessToken(session.email())
                .map(t -> HttpResponses.ok(new TokenResult(t)))
                .orElse(HttpResponses.badRequest("Not connected"));
    }

    public HttpResponse disconnect() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        clearConnection(session.email());
        recordConnectionEvent(session.email(), "disconnect", null);
        return HttpResponse.create()
                .withStatus(StatusCodes.FOUND)
                .addHeader(Location.create("/"));
    }
}
