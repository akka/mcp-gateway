package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Location;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.akka.mcp.gateway.application.OAuthAuthorizationCodeEntity;
import io.akka.mcp.gateway.application.OAuthClientEntity;
import io.akka.mcp.gateway.application.OAuthPendingAuthorizationEntity;
import io.akka.mcp.gateway.application.OAuthRefreshTokenEntity;
import io.akka.mcp.gateway.application.OidcPendingLoginEntity;
import io.akka.mcp.gateway.application.UserSessionEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OAuth 2.1 Authorization Server for MCP clients (e.g. Claude Code).
 *
 * MCP clients discover this endpoint via the {@link OAuthMetadataEndpoint} well-known documents,
 * then drive the following flow:
 *
 *   1. {@code POST /oauth2/register} — Dynamic Client Registration (RFC 7591): the client
 *      registers itself and receives a {@code client_id}, stored in {@link io.akka.mcp.gateway.application.OAuthClientEntity}.
 *   2. {@code GET /oauth2/authorize} — Authorization: validates the client, stores the pending
 *      request in {@link io.akka.mcp.gateway.application.OAuthPendingAuthorizationEntity}, then either
 *      redirects to the consent page (existing browser session) or to Okta (no session yet).
 *   3. After Okta login, {@link AuthEndpoint} callback hands back to {@code GET /oauth2/consent}
 *      where the user approves or denies access.
 *   4. {@code POST /oauth2/consent} — issues an authorization code stored in
 *      {@link io.akka.mcp.gateway.application.OAuthAuthorizationCodeEntity} and redirects to the client.
 *   5. {@code POST /oauth2/token} — Token exchange: validates the auth code + PKCE verifier and
 *      returns the user's session token as a Bearer access token, plus a refresh token stored in
 *      {@link io.akka.mcp.gateway.application.OAuthRefreshTokenEntity}. Supports {@code refresh_token}
 *      grant for silent renewal (token rotation).
 *
 * The issued access token is the user's {@link io.akka.mcp.gateway.application.UserSessionEntity} token,
 * so all subsequent MCP calls authenticated with it go through the same session as the browser.
 */
@HttpEndpoint("/oauth2")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class McpOAuthEndpoint extends AbstractProtectedEndpoint {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthEndpoint.class);

    private final String baseUrl;
    private final String oktaAuthorizationEndpoint;
    private final String oktaClientId;
    private final String oktaRedirectUri;
    private final String oktaBaseUrl;
    private final String oktaApiToken;

    public McpOAuthEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
        this.baseUrl = mcpBaseUrl;
        this.oktaClientId = config.getString("okta.client-id");
        this.oktaRedirectUri = config.getString("okta.redirect-uri");
        this.oktaApiToken = config.getString("okta.api-token");

        // Discover Okta authorization endpoint from issuer URL
        String issuerUrl = config.getString("okta.issuer-url");
        String authEp = "";
        if (!issuerUrl.isBlank()) {
            try {
                String base = issuerUrl.endsWith("/") ? issuerUrl.substring(0, issuerUrl.length() - 1) : issuerUrl;
                String discoveryUrl = base + "/.well-known/openid-configuration";
                var resp = java.net.http.HttpClient.newHttpClient().send(
                        java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(discoveryUrl))
                                .GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
                    authEp = json.path("authorization_endpoint").asText("");
                }
            } catch (Exception e) {
                log.error("OIDC discovery failed: {}", e.getMessage());
            }
        }
        this.oktaAuthorizationEndpoint = authEp;

        String base = "";
        if (!issuerUrl.isBlank()) {
            try {
                java.net.URI u = java.net.URI.create(issuerUrl);
                base = u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
            } catch (Exception e) {
                log.warn("Could not parse OKTA_ISSUER_URL for base URL: {}", e.getMessage());
            }
        }
        this.oktaBaseUrl = base;
    }

    /**
     * Re-fetches the user's current Okta group membership via the Admin API, rather than
     * trusting the (potentially stale) groups captured at the original authorization. Without
     * this, a refresh_token grant would keep re-stamping whatever groups existed when the MCP
     * client first authorized, so a role revoked in Okta later would never take effect for as
     * long as the client keeps refreshing. Falls back to the stored groups if the lookup fails
     * or isn't configured, so a transient Okta outage doesn't break token refresh.
     */
    private List<String> fetchCurrentGroups(String email, List<String> fallback) {
        if (oktaApiToken.isBlank() || oktaBaseUrl.isBlank() || email == null || email.isBlank()) {
            return fallback;
        }
        try {
            var encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8);
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(oktaBaseUrl + "/api/v1/users/" + encodedEmail + "/groups"))
                    .header("Authorization", "SSWS " + oktaApiToken)
                    .header("Accept", "application/json")
                    .GET().build();
            var resp = java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("Okta groups refresh: status={} for {}", resp.statusCode(), email);
                return fallback;
            }
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
            if (!json.isArray()) return fallback;
            var groups = new java.util.ArrayList<String>();
            for (var g : json) {
                var name = g.path("profile").path("name").asText("");
                if (!name.isBlank() && !groups.contains(name)) groups.add(name);
            }
            return groups;
        } catch (Exception e) {
            log.warn("Okta groups refresh failed for {}: {}", email, e.getMessage());
            return fallback;
        }
    }

    // ── Dynamic Client Registration ─────────────────────────────────────────

    public record RegisterRequest(List<String> redirect_uris, String client_name) {}
    public record RegisterResponse(String client_id, String client_name, List<String> redirect_uris) {}

    @Post("/register")
    public HttpResponse register(RegisterRequest req) {
        if (req.redirect_uris() == null || req.redirect_uris().isEmpty()) {
            return HttpResponse.create()
                    .withStatus(StatusCodes.BAD_REQUEST)
                    .withEntity(ContentTypes.APPLICATION_JSON,
                            "{\"error\":\"invalid_request\",\"error_description\":\"redirect_uris is required\"}");
        }

        String clientId = UUID.randomUUID().toString();
        String clientName = req.client_name() != null ? req.client_name() : "MCP Client";
        String redirectUri = req.redirect_uris().get(0);

        componentClient
                .forKeyValueEntity(clientId)
                .method(OAuthClientEntity::register)
                .invoke(new OAuthClientEntity.RegisterCommand(clientId, clientName, redirectUri));

        return HttpResponse.create()
                .withStatus(StatusCodes.CREATED)
                .withEntity(ContentTypes.APPLICATION_JSON,
                        toJson(new RegisterResponse(clientId, clientName, List.of(redirectUri))));
    }

    // ── Authorization Endpoint ──────────────────────────────────────────────

    @Get("/authorize")
    public HttpResponse authorize() {
        var params = requestContext().queryParams();
        String clientId = params.getString("client_id").orElse(null);
        String redirectUri = params.getString("redirect_uri").orElse(null);
        String responseType = params.getString("response_type").orElse(null);
        String clientState = params.getString("state").orElse("");
        String codeChallenge = params.getString("code_challenge").orElse(null);
        String codeChallengeMethod = params.getString("code_challenge_method").orElse(null);
        String scope = params.getString("scope").orElse("mcp:read");

        if (clientId == null || redirectUri == null || codeChallenge == null) {
            return errorPage("Missing required parameter: client_id, redirect_uri, or code_challenge");
        }
        if (!"code".equals(responseType)) {
            return errorPage("response_type must be 'code'");
        }
        if (!"S256".equals(codeChallengeMethod)) {
            return errorPage("code_challenge_method must be 'S256'");
        }

        var client = componentClient
                .forKeyValueEntity(clientId)
                .method(OAuthClientEntity::get)
                .invoke();

        if (client.isEmpty()) {
            return errorPage("Unknown client_id");
        }
        if (!redirectUri.equals(client.redirectUri())) {
            return errorPage("redirect_uri mismatch");
        }

        String oauthState = UUID.randomUUID().toString();
        var pendingCmd = new OAuthPendingAuthorizationEntity.CreateCommand(
                clientId, redirectUri, codeChallenge, codeChallengeMethod,
                scope, clientState, Instant.now().plusSeconds(600));

        componentClient
                .forKeyValueEntity(oauthState)
                .method(OAuthPendingAuthorizationEntity::create)
                .invoke(pendingCmd);

        // If the user already has a valid app session, skip Okta and go straight to consent.
        // Their identity was already verified when they logged in via Okta originally.
        var session = requireSession();
        if (session != null) {
            log.debug("authorize: existing session found, redirecting to consent");
            return HttpResponse.create()
                    .withStatus(StatusCodes.FOUND)
                    .addHeader(Location.create("/oauth2/consent?oauth_state=" + oauthState));
        }

        // No session — route through Okta so the user authenticates first.
        if (oktaAuthorizationEndpoint.isBlank()) {
            return errorPage("Okta is not configured");
        }

        String codeVerifier = generateCodeVerifier();
        String oktaChallenge = pkceChallenge(codeVerifier);
        componentClient
                .forKeyValueEntity(oauthState)
                .method(OidcPendingLoginEntity::create)
                .invoke(new OidcPendingLoginEntity.CreateCommand("", Instant.now().plusSeconds(600), codeVerifier));

        String oktaUrl = oktaAuthorizationEndpoint
                + "?response_type=code"
                + "&client_id=" + encode(oktaClientId)
                + "&redirect_uri=" + encode(oktaRedirectUri)
                + "&scope=" + encode("openid profile email")
                + "&state=" + encode(oauthState)
                + "&code_challenge_method=S256"
                + "&code_challenge=" + encode(oktaChallenge);

        log.debug("authorize: no session, redirecting to Okta");
        return HttpResponse.create()
                .withStatus(StatusCodes.FOUND)
                .addHeader(Location.create(oktaUrl));
    }

    // ── Consent Page ────────────────────────────────────────────────────────

    @Get("/consent")
    public HttpResponse consentPage() {
        var session = requireSession();
        if (session == null) {
            return HttpResponse.create()
                    .withStatus(StatusCodes.FOUND)
                    .addHeader(Location.create("/login"));
        }

        String oauthState = requestContext().queryParams().getString("oauth_state").orElse(null);
        if (oauthState == null) {
            return errorPage("Missing oauth_state parameter");
        }

        var pending = componentClient
                .forKeyValueEntity(oauthState)
                .method(OAuthPendingAuthorizationEntity::get)
                .invoke();

        if (pending.isEmpty() || pending.isExpired()) {
            return errorPage("OAuth session expired or not found. Please try connecting again.");
        }

        var client = componentClient
                .forKeyValueEntity(pending.clientId())
                .method(OAuthClientEntity::get)
                .invoke();

        String clientName = client.isEmpty() ? pending.clientId() : client.clientName();
        String scopeLabel = "mcp:read".equals(pending.scope())
                ? "read access to support tickets and CRM data"
                : pending.scope();

        String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Authorize Access - Akka MCP Gateway</title>
                  <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                           background: #f5f5f5; display: flex; align-items: center;
                           justify-content: center; min-height: 100vh; padding: 1rem; }
                    .card { background: #fff; border-radius: 8px; box-shadow: 0 2px 12px rgba(0,0,0,.12);
                            max-width: 440px; width: 100%%; padding: 2rem; }
                    h1 { font-size: 1.4rem; margin-bottom: .5rem; color: #111; }
                    .sub { color: #555; margin-bottom: 1.5rem; font-size: .95rem; }
                    .scope-box { background: #f9f9f9; border: 1px solid #e0e0e0; border-radius: 6px;
                                 padding: 1rem; margin-bottom: 1.5rem; }
                    .scope-box p { font-size: .9rem; color: #333; margin-bottom: .25rem; }
                    .scope-box strong { color: #111; }
                    .user { font-size: .85rem; color: #777; margin-bottom: 1.5rem; }
                    .buttons { display: flex; gap: .75rem; }
                    button { flex: 1; padding: .7rem 1rem; border: none; border-radius: 6px;
                             font-size: 1rem; cursor: pointer; transition: opacity .15s; }
                    button:hover { opacity: .85; }
                    .allow { background: #0077cc; color: #fff; font-weight: 600; }
                    .deny  { background: #e5e7eb; color: #333; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>Authorize Access</h1>
                    <p class="sub"><strong>%s</strong> is requesting permission to:</p>
                    <div class="scope-box">
                      <p>&#10003;&nbsp; <strong>%s</strong></p>
                    </div>
                    <p class="user">Signed in as <strong>%s</strong></p>
                    <form method="POST" action="/oauth2/consent">
                      <input type="hidden" name="oauth_state" value="%s">
                      <div class="buttons">
                        <button type="submit" name="action" value="allow" class="allow">Allow</button>
                        <button type="submit" name="action" value="deny"  class="deny">Deny</button>
                      </div>
                    </form>
                  </div>
                </body>
                </html>
                """.formatted(
                escapeHtml(clientName),
                escapeHtml(scopeLabel),
                escapeHtml(session.email()),
                escapeHtml(oauthState));

        return HttpResponse.create()
                .withStatus(StatusCodes.OK)
                .withEntity(ContentTypes.TEXT_HTML_UTF8, html);
    }

    // ── Consent Submit ───────────────────────────────────────────────────────

    @Post("/consent")
    public HttpResponse consentSubmit(akka.http.javadsl.model.HttpEntity.Strict body) {
        var session = requireSession();
        if (session == null) {
            return HttpResponse.create()
                    .withStatus(StatusCodes.FOUND)
                    .addHeader(Location.create("/login"));
        }

        Map<String, String> form = parseFormBody(body.getData().utf8String());
        String oauthState = form.get("oauth_state");
        String action = form.get("action");

        if (oauthState == null) {
            return errorPage("Missing oauth_state");
        }

        var pending = componentClient
                .forKeyValueEntity(oauthState)
                .method(OAuthPendingAuthorizationEntity::get)
                .invoke();

        if (pending.isEmpty() || pending.isExpired()) {
            return errorPage("OAuth session expired or not found. Please try connecting again.");
        }

        componentClient
                .forKeyValueEntity(oauthState)
                .method(OAuthPendingAuthorizationEntity::delete)
                .invoke();

        String redirectBase = pending.redirectUri();
        String clientState = pending.clientState() != null ? pending.clientState() : "";

        if ("allow".equals(action)) {
            String authCode = UUID.randomUUID().toString();
            String sessionToken = getSessionToken();

            componentClient
                    .forKeyValueEntity(authCode)
                    .method(OAuthAuthorizationCodeEntity::create)
                    .invoke(new OAuthAuthorizationCodeEntity.CreateCommand(
                            authCode, pending.clientId(), sessionToken,
                            pending.redirectUri(), pending.codeChallenge(), pending.codeChallengeMethod(),
                            pending.scope(), Instant.now().plusSeconds(600)));

            String location = redirectBase
                    + (redirectBase.contains("?") ? "&" : "?")
                    + "code=" + encode(authCode)
                    + "&state=" + encode(clientState);

            return HttpResponse.create()
                    .withStatus(StatusCodes.FOUND)
                    .addHeader(Location.create(location));
        } else {
            String location = redirectBase
                    + (redirectBase.contains("?") ? "&" : "?")
                    + "error=access_denied"
                    + "&state=" + encode(clientState);

            return HttpResponse.create()
                    .withStatus(StatusCodes.FOUND)
                    .addHeader(Location.create(location));
        }
    }

    // ── Token Endpoint ───────────────────────────────────────────────────────

    private static final long ACCESS_TOKEN_TTL_SECONDS = 3600; // 1 hour
    private static final long REFRESH_TOKEN_TTL_DAYS   = 30;

    public record TokenResponse(String access_token, String token_type, long expires_in, String refresh_token) {}

    @Post("/token")
    public HttpResponse token(akka.http.javadsl.model.HttpEntity.Strict body) {
        Map<String, String> form = parseFormBody(body.getData().utf8String());
        String grantType = form.get("grant_type");

        if ("authorization_code".equals(grantType)) {
            return handleAuthorizationCodeGrant(form);
        } else if ("refresh_token".equals(grantType)) {
            return handleRefreshTokenGrant(form);
        } else {
            return tokenError(StatusCodes.BAD_REQUEST, "unsupported_grant_type",
                    "Supported grant types: authorization_code, refresh_token");
        }
    }

    private HttpResponse handleAuthorizationCodeGrant(Map<String, String> form) {
        String code        = form.get("code");
        String redirectUri = form.get("redirect_uri");
        String clientId    = form.get("client_id");
        String codeVerifier = form.get("code_verifier");

        if (code == null || redirectUri == null || clientId == null || codeVerifier == null) {
            return tokenError(StatusCodes.BAD_REQUEST, "invalid_request", "Missing required parameter");
        }

        var authCode = componentClient.forKeyValueEntity(code)
                .method(OAuthAuthorizationCodeEntity::get).invoke();

        if (authCode.isEmpty() || authCode.isExpired() || authCode.used()) {
            return tokenError(StatusCodes.BAD_REQUEST, "invalid_grant",
                    "Authorization code is invalid, expired, or already used");
        }
        if (!clientId.equals(authCode.clientId())) {
            return tokenError(StatusCodes.BAD_REQUEST, "invalid_client", "client_id does not match");
        }
        if (!redirectUri.equals(authCode.redirectUri())) {
            return tokenError(StatusCodes.BAD_REQUEST, "invalid_grant", "redirect_uri mismatch");
        }
        if (!pkceChallenge(codeVerifier).equals(authCode.codeChallenge())) {
            return tokenError(StatusCodes.BAD_REQUEST, "invalid_grant", "PKCE verification failed");
        }

        componentClient.forKeyValueEntity(code).method(OAuthAuthorizationCodeEntity::markUsed).invoke();

        var userSession = componentClient.forKeyValueEntity(authCode.sessionToken())
                .method(UserSessionEntity::getSession).invoke();

        long expiresIn = ACCESS_TOKEN_TTL_SECONDS;
        if (!userSession.isEmpty() && !userSession.isExpired()) {
            long remaining = userSession.expiresAt().getEpochSecond() - Instant.now().getEpochSecond();
            if (remaining > 0) expiresIn = remaining;
        }

        String refreshToken = issueRefreshToken(
                userSession.isEmpty() ? "" : userSession.email(),
                userSession.isEmpty() ? "" : userSession.displayName(),
                clientId,
                userSession.isEmpty() ? List.of() : userSession.groups());

        return HttpResponse.create()
                .withStatus(StatusCodes.OK)
                .withEntity(ContentTypes.APPLICATION_JSON,
                        toJson(new TokenResponse(authCode.sessionToken(), "Bearer", expiresIn, refreshToken)));
    }

    private HttpResponse handleRefreshTokenGrant(Map<String, String> form) {
        String refreshTokenValue = form.get("refresh_token");
        String clientId          = form.get("client_id");

        if (refreshTokenValue == null || clientId == null) {
            return tokenError(StatusCodes.BAD_REQUEST, "invalid_request",
                    "Missing required parameter: refresh_token, client_id");
        }

        var storedToken = componentClient.forKeyValueEntity(refreshTokenValue)
                .method(OAuthRefreshTokenEntity::get).invoke();

        if (!storedToken.isValid()) {
            return tokenError(StatusCodes.BAD_REQUEST, "invalid_grant",
                    "Refresh token is invalid, expired, or revoked");
        }
        if (!clientId.equals(storedToken.clientId())) {
            return tokenError(StatusCodes.BAD_REQUEST, "invalid_client", "client_id does not match");
        }

        // Revoke old refresh token (rotation)
        componentClient.forKeyValueEntity(refreshTokenValue)
                .method(OAuthRefreshTokenEntity::revoke).invoke();

        // Re-check current group membership against Okta rather than trusting the groups
        // captured at the original authorization — otherwise a role revoked since then would
        // never take effect for as long as the client keeps refreshing.
        List<String> currentGroups = fetchCurrentGroups(storedToken.userId(), storedToken.groups());

        // Create a new session for the user
        String newSessionToken = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(newSessionToken)
                .method(UserSessionEntity::create)
                .invoke(new UserSessionEntity.CreateCommand(
                        storedToken.userId(),
                        storedToken.displayName(),
                        Instant.now().plusSeconds(ACCESS_TOKEN_TTL_SECONDS),
                        currentGroups,
                        null,
                        List.of()));

        // Issue a new refresh token
        String newRefreshToken = issueRefreshToken(
                storedToken.userId(), storedToken.displayName(),
                clientId, currentGroups);

        return HttpResponse.create()
                .withStatus(StatusCodes.OK)
                .withEntity(ContentTypes.APPLICATION_JSON,
                        toJson(new TokenResponse(newSessionToken, "Bearer", ACCESS_TOKEN_TTL_SECONDS, newRefreshToken)));
    }

    private String issueRefreshToken(String userId, String displayName, String clientId, List<String> groups) {
        String token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(OAuthRefreshTokenEntity::create)
                .invoke(new OAuthRefreshTokenEntity.CreateCommand(
                        token, userId, displayName, clientId, groups,
                        Instant.now().plus(REFRESH_TOKEN_TTL_DAYS, ChronoUnit.DAYS)));
        return token;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    static String pkceChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, String> parseFormBody(String body) {
        Map<String, String> result = new HashMap<>();
        if (body == null || body.isBlank()) return result;
        for (String pair : body.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = java.net.URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String val = java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                result.put(key, val);
            }
        }
        return result;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static HttpResponse errorPage(String message) {
        String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="UTF-8"><title>Error - Akka MCP Gateway</title>
                <style>body{font-family:sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;background:#f5f5f5;}
                .card{background:#fff;border-radius:8px;box-shadow:0 2px 12px rgba(0,0,0,.12);max-width:440px;width:100%%;padding:2rem;}
                h1{color:#c00;margin-bottom:.75rem;}p{color:#333;}</style></head>
                <body><div class="card"><h1>Authorization Error</h1><p>%s</p></div></body>
                </html>
                """.formatted(escapeHtml(message));
        return HttpResponse.create()
                .withStatus(StatusCodes.BAD_REQUEST)
                .withEntity(ContentTypes.TEXT_HTML_UTF8, html);
    }

    private static HttpResponse tokenError(akka.http.javadsl.model.StatusCode status, String error, String description) {
        return HttpResponse.create()
                .withStatus(status)
                .withEntity(ContentTypes.APPLICATION_JSON,
                        "{\"error\":\"" + error + "\",\"error_description\":\"" + description + "\"}");
    }

    private static String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failed", e);
        }
    }
}
