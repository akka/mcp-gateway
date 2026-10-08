package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Location;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import io.akka.mcp.gateway.application.OAuthPendingAuthorizationEntity;
import io.akka.mcp.gateway.application.OidcPendingLoginEntity;
import io.akka.mcp.gateway.application.UserSessionEntity;
import io.akka.mcp.gateway.application.UserSessionsByEmailView;
import io.akka.mcp.gateway.domain.UserSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Handles end-user authentication via Okta OIDC and exposes the browser-facing session API.
 *
 * On startup, performs OIDC discovery against {@code OKTA_ISSUER_URL} to resolve all
 * endpoints (authorize, token, userinfo) so no endpoint URLs are hardcoded.
 *
 * Login flow (PKCE):
 *   1. {@code /login} forwards the browser to {@code /auth/start} (unless the user just signed out).
 *   2. A PKCE pair is generated and the pending state is stored in {@link io.akka.mcp.gateway.application.OidcPendingLoginEntity}.
 *   3. Browser is redirected to Okta with the code_challenge; with a live Okta session Okta answers without a prompt.
 *   4. Okta redirects back to {@code /auth/callback} with an auth code.
 *   5. The code is exchanged for tokens; userinfo is fetched to get email, name, and group entitlements.
 *      The email domain is checked here, against the identity Okta vouched for.
 *   6. A {@link io.akka.mcp.gateway.application.UserSessionEntity} is created, a {@code SESSION} cookie is set,
 *      and the browser lands on the page it originally asked for.
 *
 * If the callback's {@code state} matches a pending OAuth 2.1 authorization request
 * (initiated by an MCP client via {@link McpOAuthEndpoint}), the user is forwarded to
 * the consent page instead of the main dashboard.
 *
 * Other routes: {@code /auth/me} (current user info), {@code /auth/logout} (ends the gateway session only; the Okta session is kept),
 * {@code /auth/okta-status} (admin lookup).
 */
@HttpEndpoint("/")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class AuthEndpoint extends AbstractProtectedEndpoint {

    private static final Logger log = LoggerFactory.getLogger(AuthEndpoint.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String authorizationEndpoint;
    private final String tokenEndpoint;
    private final String userinfoEndpoint;
    private final String oktaBaseUrl;
    private final String oktaApiToken;
    private final String allowedEmailDomain;

    public record MeResponse(String email, String displayName, List<String> groups, List<UserSession.App> apps, boolean canAdmin) {}
    public record OktaUserStatusResponse(
            String login, String email, String firstName, String lastName,
            String status, String created, String lastLogin,
            String passwordChanged, String statusChanged) {}

    public AuthEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
        this.clientId = config.getString("okta.client-id");
        this.clientSecret = config.getString("okta.client-secret");
        this.redirectUri = config.getString("okta.redirect-uri");
        this.oktaApiToken = config.getString("okta.api-token");
        this.allowedEmailDomain = config.getString("okta.allowed-email-domain");
        String issuerUrl = config.getString("okta.issuer-url");

        String authEp = "", tokenEp = "", userinfoEp = "";
        if (!issuerUrl.isBlank()) {
            try {
                String base = issuerUrl.endsWith("/") ? issuerUrl.substring(0, issuerUrl.length() - 1) : issuerUrl;
                String discoveryUrl = base + "/.well-known/openid-configuration";
                log.info("OIDC discovery: fetching {}", discoveryUrl);
                var resp = HTTP_CLIENT.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(discoveryUrl))
                                .GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    log.error("OIDC discovery failed: HTTP {} body={}", resp.statusCode(), resp.body());
                } else {
                    var json = MAPPER.readTree(resp.body());
                    authEp = json.path("authorization_endpoint").asText("");
                    tokenEp = json.path("token_endpoint").asText("");
                    userinfoEp = json.path("userinfo_endpoint").asText("");
                    log.info("OIDC discovery OK: authorization_endpoint={}", authEp);
                }
            } catch (Exception e) {
                log.error("OIDC discovery failed", e);
            }
        } else {
            log.warn("OIDC discovery skipped: OKTA_ISSUER_URL is blank");
        }
        this.authorizationEndpoint = authEp;
        this.tokenEndpoint = tokenEp;
        this.userinfoEndpoint = userinfoEp;

        String baseUrl = "";
        if (!issuerUrl.isBlank()) {
            try {
                URI u = URI.create(issuerUrl);
                baseUrl = u.getScheme() + "://" + u.getHost()
                        + (u.getPort() > 0 ? ":" + u.getPort() : "");
            } catch (Exception e) {
                log.warn("Could not parse OKTA_ISSUER_URL for base URL: {}", e.getMessage());
            }
        }
        this.oktaBaseUrl = baseUrl;
    }

    @Get("")
    public HttpResponse index() {
        if (requireSession() == null) return redirectToLogin("/");
        return HttpResponses.staticResource("index.html");
    }

    @Get("/login")
    public HttpResponse loginPage() {
        if (requireSession() != null) return redirectTo("/?flash=already-logged-in");
        // Never reuse a cached copy: a stale login page from an older deploy breaks the sign-in.
        return HttpResponses.staticResource("login.html")
                .addHeader(RawHeader.create("Cache-Control", "no-cache"));
    }

    /**
     * Starts the Okta sign-in. No email is asked for: Okta identifies the user itself, so with a
     * live Okta session this is a few silent redirects. {@code return_to} is the gateway path to
     * land on afterwards.
     */
    @Get("/auth/start")
    public HttpResponse start() {
        if (authorizationEndpoint.isBlank()) {
            return HttpResponses.internalServerError(
                    "Okta is not configured. Set OKTA_ISSUER_URL, OKTA_CLIENT_ID, and OKTA_REDIRECT_URI.");
        }
        String returnTo = safeReturnTo(requestContext().queryParams().getString("return_to").orElse(null));
        String state = UUID.randomUUID().toString();
        String codeVerifier = generateCodeVerifier();
        String codeChallenge = generateCodeChallenge(codeVerifier);
        componentClient
                .forKeyValueEntity(state)
                .method(OidcPendingLoginEntity::create)
                .invoke(new OidcPendingLoginEntity.CreateCommand("", Instant.now().plusSeconds(600), codeVerifier, returnTo));

        String authUrl = authorizationEndpoint
                + "?response_type=code"
                + "&client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode("openid profile email")
                + "&state=" + encode(state)
                + "&code_challenge_method=S256"
                + "&code_challenge=" + encode(codeChallenge);

        return HttpResponse.create()
                .withStatus(StatusCodes.FOUND)
                .addHeader(Location.create(authUrl));
    }

    /**
     * Only a local gateway path is accepted, so the sign-in can't be turned into an open redirect;
     * anything else lands on the dashboard.
     */
    static String safeReturnTo(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")
                || path.startsWith("/login") || path.startsWith("/auth/")) return "/";
        for (char c : path.toCharArray()) {
            if (c < 0x20 || c == 0x7f || c == '\\') return "/";
        }
        return path;
    }

    /** Whether the email is permitted to sign in. A blank allowed-domain means no restriction. */
    private boolean emailDomainAllowed(String email) {
        if (allowedEmailDomain == null || allowedEmailDomain.isBlank()) return true;
        return email.toLowerCase().endsWith("@" + allowedEmailDomain.toLowerCase());
    }

    @Get("/auth/callback")
    public HttpResponse callback() {
        String code = requestContext().queryParams().getString("code").orElse(null);
        String state = requestContext().queryParams().getString("state").orElse(null);
        String error = requestContext().queryParams().getString("error").orElse(null);
        String errorDesc = requestContext().queryParams().getString("error_description").orElse(null);

        log.debug("callback: code={} state={} error={} error_description={}",
                code != null ? "present" : "null", state != null ? "present" : "null", error, errorDesc);

        if (error != null) {
            return HttpResponses.badRequest("Okta error: " + error + " — " + errorDesc);
        }
        if (code == null || state == null) {
            return HttpResponses.badRequest("Missing required parameters");
        }

        var pending = componentClient
                .forKeyValueEntity(state)
                .method(OidcPendingLoginEntity::get)
                .invoke();

        if (pending.isEmpty() || pending.isExpired()) {
            return HttpResponses.badRequest("Invalid or expired login session. Please try again.");
        }

        componentClient.forKeyValueEntity(state).method(OidcPendingLoginEntity::delete).invoke();

        String accessToken;
        String idToken = "";
        try {
            String formBody = "grant_type=authorization_code"
                    + "&code=" + encode(code)
                    + "&client_id=" + encode(clientId)
                    + "&client_secret=" + encode(clientSecret)
                    + "&redirect_uri=" + encode(redirectUri)
                    + "&code_verifier=" + encode(pending.codeVerifier());

            var tokenResp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(tokenEndpoint))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(formBody))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (tokenResp.statusCode() != 200) {
                return HttpResponse.create()
                        .withStatus(StatusCodes.BAD_GATEWAY)
                        .withEntity(ContentTypes.TEXT_PLAIN_UTF8,
                                "Token exchange failed: " + tokenResp.body());
            }
            var tokenJson = MAPPER.readTree(tokenResp.body());
            accessToken = tokenJson.path("access_token").asText();
            idToken = tokenJson.path("id_token").asText("");
        } catch (Exception e) {
            return HttpResponse.create()
                    .withStatus(StatusCodes.BAD_GATEWAY)
                    .withEntity(ContentTypes.TEXT_PLAIN_UTF8,
                            "Token exchange failed: " + e.getMessage());
        }

        String email;
        String displayName;
        var groups = new java.util.ArrayList<String>();
        try {
            var userResp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(userinfoEndpoint))
                            .header("Authorization", "Bearer " + accessToken)
                            .GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (userResp.statusCode() != 200) {
                return HttpResponse.create()
                        .withStatus(StatusCodes.BAD_GATEWAY)
                        .withEntity(ContentTypes.TEXT_PLAIN_UTF8, "User info fetch failed");
            }
            var userJson = MAPPER.readTree(userResp.body());
            log.debug("userinfo claims: {}", userResp.body());
            email = userJson.path("email").asText();
            displayName = userJson.path("name").asText(email);
            var groupsNode = userJson.path("Entitlements");
            if (groupsNode.isArray()) {
                for (var g : groupsNode) {
                    var name = g.asText();
                    if (!groups.contains(name)) groups.add(name);
                }
            }
        } catch (Exception e) {
            return HttpResponse.create()
                    .withStatus(StatusCodes.BAD_GATEWAY)
                    .withEntity(ContentTypes.TEXT_PLAIN_UTF8,
                            "User info fetch failed: " + e.getMessage());
        }
        if (email.isBlank() || !emailDomainAllowed(email)) {
            log.warn("callback rejected: email domain not @{} (email={})", allowedEmailDomain, email);
            return HttpResponse.create()
                    .withStatus(StatusCodes.FORBIDDEN)
                    .withEntity(ContentTypes.TEXT_PLAIN_UTF8,
                            "Sign-in is restricted to @" + allowedEmailDomain + " accounts.");
        }

        var apps = new java.util.ArrayList<UserSession.App>();
        if (oktaApiToken.isBlank()) {
            log.warn("Okta apps: skipping — MCP_PROXY_OKTA_API_TOKEN is blank");
        } else if (oktaBaseUrl.isBlank()) {
            log.warn("Okta apps: skipping — oktaBaseUrl is blank (check OKTA_ISSUER_URL)");
        } else {
            try {
                var encodedEmail = URLEncoder.encode(email, StandardCharsets.UTF_8);
                var url = oktaBaseUrl + "/api/v1/users/" + encodedEmail + "/appLinks";
                log.info("Okta apps: fetching {}", url);
                var appsResp = HTTP_CLIENT.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .header("Authorization", "SSWS " + oktaApiToken)
                                .header("Accept", "application/json")
                                .GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                log.info("Okta apps: status={} bodyLength={}", appsResp.statusCode(), appsResp.body().length());
                if (appsResp.statusCode() == 200) {
                    log.debug("Okta apps: body={}", appsResp.body());
                    var appsJson = MAPPER.readTree(appsResp.body());
                    if (appsJson.isArray()) {
                        for (var app : appsJson) {
                            var appInstanceId = app.path("appInstanceId").asText("");
                            var label = app.path("label").asText("");
                            log.info("Okta apps: appInstanceId={} label={}", appInstanceId, label);
                            if (!appInstanceId.isBlank() && apps.stream().noneMatch(a -> a.id().equals(appInstanceId))) {
                                apps.add(new UserSession.App(appInstanceId, label.isBlank() ? appInstanceId : label));
                            }
                        }
                    } else {
                        log.warn("Okta apps: response is not a JSON array");
                    }
                } else {
                    log.warn("Okta apps: error status={} body={}", appsResp.statusCode(), appsResp.body());
                }
            } catch (Exception e) {
                log.warn("Okta apps: exception — {}", e.getMessage(), e);
            }
        }
        log.info("Okta apps: resolved {} apps for {}: {}", apps.size(), email, apps);

        // Check if this Okta flow was initiated by an OAuth 2.1 authorize request
        var pendingOAuth = componentClient
                .forKeyValueEntity(state)
                .method(OAuthPendingAuthorizationEntity::get)
                .invoke();

        // Flush any other still-valid sessions for this user before creating the new one, so a
        // fresh login (which just re-verified current Okta group membership) can't be undercut
        // by an older session elsewhere still running on stale, possibly-since-revoked groups.
        var existingSessions = componentClient
                .forView()
                .method(UserSessionsByEmailView::getByEmail)
                .invoke(new UserSessionsByEmailView.ByEmail(email));
        for (var existing : existingSessions.sessions()) {
            componentClient
                    .forKeyValueEntity(existing.sessionToken())
                    .method(UserSessionEntity::invalidate)
                    .invoke();
        }

        String sessionToken = UUID.randomUUID().toString();
        componentClient
                .forKeyValueEntity(sessionToken)
                .method(UserSessionEntity::create)
                .invoke(new UserSessionEntity.CreateCommand(email, displayName,
                        Instant.now().plusSeconds(8 * 3600), List.copyOf(groups), idToken, List.copyOf(apps)));

        if (!pendingOAuth.isEmpty() && !pendingOAuth.isExpired()) {
            return HttpResponse.create()
                    .withStatus(StatusCodes.FOUND)
                    .addHeader(Location.create("/oauth2/consent?oauth_state=" + state))
                    .addHeader(RawHeader.create("Set-Cookie",
                            "SESSION=" + sessionToken + "; HttpOnly; SameSite=Lax; Path=/"));
        }

        return HttpResponse.create()
                .withStatus(StatusCodes.FOUND)
                .addHeader(Location.create(pending.returnTo() == null ? "/" : pending.returnTo()))
                .addHeader(RawHeader.create("Set-Cookie",
                        "SESSION=" + sessionToken + "; HttpOnly; SameSite=Lax; Path=/"));
    }

    /**
     * Signs out of the gateway only. The Okta session is left alone, so other Okta apps keep
     * working; {@code flash=logout} keeps the login page from signing the user straight back in.
     */
    @Get("/auth/logout")
    public HttpResponse logout() {
        String sessionToken = getSessionToken();
        if (sessionToken != null && !sessionToken.isBlank()) {
            try {
                componentClient.forKeyValueEntity(sessionToken).method(UserSessionEntity::invalidate).invoke();
            } catch (Exception ignored) {}
        }
        return HttpResponse.create()
                .withStatus(StatusCodes.FOUND)
                .addHeader(Location.create("/login?flash=logout"))
                .addHeader(RawHeader.create("Set-Cookie", "SESSION=; Max-Age=0; HttpOnly; Path=/"));
    }

    @Get("/auth/permissions")
    public HttpResponse permissionsPage() {
        var session = requireSession();
        if (session == null) return redirectToLogin("/auth/permissions");
        return HttpResponses.staticResource("permissions.html");
    }

    @Get("/how-to-use")
    public HttpResponse howToUsePage() {
        var session = requireSession();
        if (session == null) return redirectToLogin("/how-to-use");
        return HttpResponses.staticResource("how-to-use.html");
    }


    @Get("/auth/me")
    public HttpResponse me() {
        var session = requireSession();
        if (session == null) {
            return HttpResponse.create()
                    .withStatus(StatusCodes.UNAUTHORIZED)
                    .withEntity(ContentTypes.TEXT_PLAIN_UTF8, "Not authenticated");
        }
        return HttpResponses.ok(new MeResponse(session.email(), session.displayName(), session.groups(), session.apps(), session.isAdmin(adminGroup)));
    }

    @Get("/okta-status")
    public HttpResponse oktaStatusPage() {
        var session = requireSession();
        if (session == null) return redirectToLogin("/okta-status");
        var denied = requireAdmin(session);
        if (denied != null) return denied;
        return HttpResponses.staticResource("okta-status.html");
    }

    @Get("/auth/okta-status")
    public HttpResponse oktaStatus() {
        var session = requireSession();
        if (session == null) return unauthorized();
        var denied = requireAdmin(session);
        if (denied != null) return denied;

        String apiToken = oktaApiToken.trim();
        if (apiToken.isBlank())
            return HttpResponses.internalServerError("MCP_PROXY_OKTA_API_TOKEN is not configured");
        if (oktaBaseUrl.isBlank())
            return HttpResponses.internalServerError("OKTA_ISSUER_URL is not configured or has unexpected format");

        try {
            var encodedEmail = URLEncoder.encode(session.email(), StandardCharsets.UTF_8);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(oktaBaseUrl + "/api/v1/users/" + encodedEmail))
                    .header("Authorization", "SSWS " + apiToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            var httpClient = HttpClient.newHttpClient();
            var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return HttpResponse.create()
                        .withStatus(StatusCodes.BAD_GATEWAY)
                        .withEntity(ContentTypes.TEXT_PLAIN_UTF8, "Okta API returned " + response.statusCode());
            }
            var json = new ObjectMapper().readTree(response.body());
            var profile = json.path("profile");
            return HttpResponses.ok(new OktaUserStatusResponse(
                    profile.path("login").asText(""),
                    profile.path("email").asText(""),
                    profile.path("firstName").asText(""),
                    profile.path("lastName").asText(""),
                    json.path("status").asText(""),
                    json.path("created").asText(""),
                    json.path("lastLogin").asText(""),
                    json.path("passwordChanged").asText(""),
                    json.path("statusChanged").asText("")));
        } catch (Exception e) {
            return HttpResponse.create()
                    .withStatus(StatusCodes.BAD_GATEWAY)
                    .withEntity(ContentTypes.TEXT_PLAIN_UTF8, "Okta API call failed: " + e.getMessage());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static long parseAuthTime(String idToken) {
        if (idToken == null || idToken.isBlank()) return 0;
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) return 0;
            String encoded = parts[1];
            int mod = encoded.length() % 4;
            if (mod == 2) encoded += "==";
            else if (mod == 3) encoded += "=";
            byte[] payload = Base64.getUrlDecoder().decode(encoded);
            return MAPPER.readTree(payload).path("auth_time").asLong(0);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String generateCodeChallenge(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
