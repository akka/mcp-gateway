package io.akka.mcp.gateway.testsupport;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The OAuth side of an MCP server with dynamic client registration and PKCE, mounted on a {@link FakeMcpServer}.
 * A test plays the browser with {@link #approve}, and the fake then accepts as Bearer only the access tokens it issued.
 * It checks what a real server would: the registered client, the redirect address, the PKCE verifier and the resource.
 */
public final class FakeOAuthServer {

    public record Issued(String grant, String accessToken, String refreshToken) {}

    private record CodeGrant(String challenge, String clientId, String redirectUri) {}

    private final FakeMcpServer mcp;
    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<String, CodeGrant> grantsByCode = new ConcurrentHashMap<>();
    private final Set<String> registeredClients = ConcurrentHashMap.newKeySet();
    private final Set<String> validRefreshTokens = ConcurrentHashMap.newKeySet();
    private final Set<String> issuedAccessTokens = ConcurrentHashMap.newKeySet();
    private final List<String> registrations = new CopyOnWriteArrayList<>();
    private final List<Issued> issued = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
    private volatile long accessLifetimeSeconds = 3600;
    private volatile boolean refusingRefresh;
    private volatile boolean refusingRegistration;
    private volatile boolean tokenEndpointDown;
    private volatile boolean omittingRefreshTokens;
    private volatile boolean withoutPkce;
    private volatile boolean withWrongIssuer;
    private volatile boolean scopesOnlyInServerMetadata;
    private volatile boolean withoutServerMetadata;
    private volatile boolean describingAnotherResource;
    private volatile boolean advertisingPlainHttpTokenEndpoint;
    private volatile boolean revokingNewAccessTokens;
    private volatile boolean answeringCodesWithInvalidClient;
    private volatile boolean resourceMetadataOnlyAtTheOrigin;
    private volatile boolean openIdMetadataOnly;
    private volatile boolean refreshingWithoutAnAccessToken;
    private volatile int refreshStatus;
    private volatile String refreshError = "";
    private volatile long refreshDelayMillis;

    private FakeOAuthServer(FakeMcpServer mcp) {
        this.mcp = mcp;
    }

    public static FakeOAuthServer mountedOn(FakeMcpServer mcp) {
        var fake = new FakeOAuthServer(mcp);
        mcp.serving("/.well-known/oauth-protected-resource", exchange -> {
            var atThePath = !exchange.getRequestURI().getPath().equals("/.well-known/oauth-protected-resource");
            if (atThePath && fake.resourceMetadataOnlyAtTheOrigin) fake.json(exchange, 404, "{}");
            else fake.json(exchange, 200, fake.resourceMetadata());
        });
        mcp.serving("/.well-known/oauth-authorization-server", exchange -> {
            if (fake.withoutServerMetadata || fake.openIdMetadataOnly) fake.json(exchange, 404, "{}");
            else fake.json(exchange, 200, fake.serverMetadata());
        });
        mcp.serving("/.well-known/openid-configuration", exchange -> {
            if (fake.withoutServerMetadata) fake.json(exchange, 404, "{}");
            else fake.json(exchange, 200, fake.serverMetadata());
        });
        mcp.serving("/oauth2/register", fake::register);
        mcp.serving("/oauth2/token", fake::token);
        mcp.acceptingAuthorization(header -> header != null && header.startsWith("Bearer ")
                && fake.issuedAccessTokens.contains(header.substring("Bearer ".length())));
        return fake;
    }

    public FakeOAuthServer issuingAccessTokensValidForSeconds(long seconds) {
        this.accessLifetimeSeconds = seconds;
        return this;
    }

    public FakeOAuthServer refusingEveryRefresh() {
        this.refusingRefresh = true;
        return this;
    }

    /** Answers every refresh with this status and OAuth error code. */
    public FakeOAuthServer answeringRefreshWith(int status, String error) {
        this.refreshStatus = status;
        this.refreshError = error;
        return this;
    }

    public FakeOAuthServer slowToRefresh(long millis) {
        this.refreshDelayMillis = millis;
        return this;
    }

    public FakeOAuthServer refusingRegistration() {
        this.refusingRegistration = true;
        return this;
    }

    public FakeOAuthServer withTokenEndpointDown() {
        this.tokenEndpointDown = true;
        return this;
    }

    public FakeOAuthServer issuingNoRefreshTokens() {
        this.omittingRefreshTokens = true;
        return this;
    }

    public FakeOAuthServer notOfferingPkce() {
        this.withoutPkce = true;
        return this;
    }

    public FakeOAuthServer claimingAnotherIssuer() {
        this.withWrongIssuer = true;
        return this;
    }

    public FakeOAuthServer listingScopesOnlyInTheServerMetadata() {
        this.scopesOnlyInServerMetadata = true;
        return this;
    }

    public FakeOAuthServer withoutAuthorizationServerMetadata() {
        this.withoutServerMetadata = true;
        return this;
    }

    public FakeOAuthServer publishingResourceMetadataOnlyAtTheOrigin() {
        this.resourceMetadataOnlyAtTheOrigin = true;
        return this;
    }

    public FakeOAuthServer publishingOnlyOpenIdMetadata() {
        this.openIdMetadataOnly = true;
        return this;
    }

    public FakeOAuthServer answeringRefreshesWithoutAnAccessToken() {
        this.refreshingWithoutAnAccessToken = true;
        return this;
    }

    /** Tokens as if issued earlier: accepted by the server, and not counted among the ones issued during the test. */
    public Issued issueDirectly() {
        var n = sequence.incrementAndGet();
        var tokens = new Issued("direct", "access-" + n, "refresh-" + n);
        issuedAccessTokens.add(tokens.accessToken());
        validRefreshTokens.add(tokens.refreshToken());
        return tokens;
    }

    public FakeOAuthServer describingAnotherResource() {
        this.describingAnotherResource = true;
        return this;
    }

    public FakeOAuthServer advertisingAPlainHttpTokenEndpoint() {
        this.advertisingPlainHttpTokenEndpoint = true;
        return this;
    }

    /** The server issues access tokens it does not accept, so the gateway's check of a new sign-in fails. */
    public FakeOAuthServer refusingTheAccessTokensItIssues() {
        this.revokingNewAccessTokens = true;
        return this;
    }

    public FakeOAuthServer answeringCodesWithInvalidClient() {
        this.answeringCodesWithInvalidClient = true;
        return this;
    }

    /** The server stops accepting an access token it issued, as when the user's access is revoked. */
    public void revoke(String accessToken) {
        issuedAccessTokens.remove(accessToken);
    }

    public FakeOAuthServer reset() {
        accessLifetimeSeconds = 3600;
        refusingRefresh = false;
        refusingRegistration = false;
        tokenEndpointDown = false;
        omittingRefreshTokens = false;
        withoutPkce = false;
        withWrongIssuer = false;
        scopesOnlyInServerMetadata = false;
        withoutServerMetadata = false;
        describingAnotherResource = false;
        advertisingPlainHttpTokenEndpoint = false;
        revokingNewAccessTokens = false;
        answeringCodesWithInvalidClient = false;
        resourceMetadataOnlyAtTheOrigin = false;
        openIdMetadataOnly = false;
        refreshingWithoutAnAccessToken = false;
        issuedAccessTokens.clear();
        validRefreshTokens.clear();
        grantsByCode.clear();
        registeredClients.clear();
        refreshStatus = 0;
        refreshError = "";
        refreshDelayMillis = 0;
        registrations.clear();
        issued.clear();
        tokenRequests.clear();
        return this;
    }

    public List<String> registrations() {
        return registrations;
    }

    public List<Issued> issued() {
        return issued;
    }

    public List<Map<String, String>> tokenRequests() {
        return tokenRequests;
    }

    public long refreshGrants() {
        return issued.stream().filter(token -> token.grant().equals("refresh_token")).count();
    }

    /** What the browser does at the authorization endpoint after the user approves: returns the code to hand back to the gateway. */
    public String approve(String authorizeUrl) {
        var params = queryOf(URI.create(authorizeUrl));
        if (!registeredClients.contains(params.get("client_id"))) throw new IllegalStateException("The client is not registered");
        var code = "code-" + sequence.incrementAndGet();
        grantsByCode.put(code, new CodeGrant(params.get("code_challenge"), params.get("client_id"), params.get("redirect_uri")));
        return code;
    }

    public static Map<String, String> queryOf(URI uri) {
        return parseForm(uri.getRawQuery());
    }

    private static Map<String, String> parseForm(String form) {
        var params = new HashMap<String, String>();
        for (var pair : form.split("&")) {
            var parts = pair.split("=", 2);
            params.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return params;
    }

    private String resourceMetadata() {
        var scopes = scopesOnlyInServerMetadata ? "" : ",\"scopes_supported\":[\"mcp:read\"]";
        var resource = describingAnotherResource ? "https://elsewhere.example.com/mcp" : mcp.url() + "/mcp";
        return "{\"resource\":\"" + resource + "\",\"authorization_servers\":[\"" + mcp.url() + "\"]" + scopes + "}";
    }

    private String serverMetadata() {
        var issuer = withWrongIssuer ? "https://elsewhere.example.com" : mcp.url();
        var pkce = withoutPkce ? "" : ",\"code_challenge_methods_supported\":[\"S256\"]";
        return "{\"issuer\":\"" + issuer + "\",\"authorization_endpoint\":\"" + mcp.url() + "/oauth2/authorize\","
                + "\"token_endpoint\":\"" + (advertisingPlainHttpTokenEndpoint ? "http://example.com/token" : mcp.url() + "/oauth2/token") + "\",\"registration_endpoint\":\"" + mcp.url() + "/oauth2/register\","
                + "\"scopes_supported\":[\"mcp:read\"]" + pkce + "}";
    }

    private void register(HttpExchange exchange) throws IOException {
        try (exchange) {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (refusingRegistration) {
                json(exchange, 400, "{\"error\":\"invalid_client_metadata\"}");
                return;
            }
            registrations.add(body);
            var clientId = "client-" + sequence.incrementAndGet();
            registeredClients.add(clientId);
            json(exchange, 201, "{\"client_id\":\"" + clientId + "\"}");
        }
    }

    private void token(HttpExchange exchange) throws IOException {
        try (exchange) {
            var form = parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            tokenRequests.add(form);
            if (tokenEndpointDown) {
                json(exchange, 503, "{\"error\":\"temporarily_unavailable\"}");
                return;
            }
            switch (form.getOrDefault("grant_type", "")) {
                case "authorization_code" -> answerCodeGrant(exchange, form);
                case "refresh_token" -> answerRefreshGrant(exchange, form);
                default -> json(exchange, 400, "{\"error\":\"unsupported_grant_type\"}");
            }
        }
    }

    private void answerCodeGrant(HttpExchange exchange, Map<String, String> form) throws IOException {
        if (answeringCodesWithInvalidClient) {
            json(exchange, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        var grant = grantsByCode.remove(form.getOrDefault("code", ""));
        var valid = grant != null
                && grant.clientId().equals(form.get("client_id"))
                && grant.redirectUri().equals(form.get("redirect_uri"))
                && grant.challenge().equals(s256(form.getOrDefault("code_verifier", "")))
                && (mcp.url() + "/mcp").equals(form.get("resource"));
        if (!valid) {
            json(exchange, 400, "{\"error\":\"invalid_grant\"}");
            return;
        }
        issueTokens(exchange, "authorization_code");
    }

    private void answerRefreshGrant(HttpExchange exchange, Map<String, String> form) throws IOException {
        pause(refreshDelayMillis);
        if (refreshStatus != 0) {
            json(exchange, refreshStatus, "{\"error\":\"" + refreshError + "\"}");
            return;
        }
        if (refreshingWithoutAnAccessToken) {
            json(exchange, 200, "{}");
            return;
        }
        if (refusingRefresh || !validRefreshTokens.remove(form.getOrDefault("refresh_token", ""))) {
            json(exchange, 400, "{\"error\":\"invalid_grant\"}");
            return;
        }
        issueTokens(exchange, "refresh_token");
    }

    private void issueTokens(HttpExchange exchange, String grant) throws IOException {
        var n = sequence.incrementAndGet();
        var access = "access-" + n;
        var refresh = "refresh-" + n;
        if (!revokingNewAccessTokens) issuedAccessTokens.add(access);
        var refreshField = omittingRefreshTokens ? "" : ",\"refresh_token\":\"" + refresh + "\"";
        if (!omittingRefreshTokens) validRefreshTokens.add(refresh);
        issued.add(new Issued(grant, access, omittingRefreshTokens ? "" : refresh));
        json(exchange, 200, "{\"access_token\":\"" + access + "\"" + refreshField + ",\"token_type\":\"Bearer\",\"expires_in\":" + accessLifetimeSeconds + "}");
    }

    private static void pause(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String s256(String verifier) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void json(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
