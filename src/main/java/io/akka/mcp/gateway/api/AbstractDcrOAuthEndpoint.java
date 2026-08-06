package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Location;
import akka.javasdk.annotations.http.Get;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Base class for MCP OAuth connections that use Dynamic Client Registration (RFC 7591).
 * The client has no pre-registered identity: on every connect it probes the MCP server,
 * discovers the authorization server via Protected Resource Metadata (RFC 9728), and
 * registers itself dynamically to obtain a client_id before starting the PKCE flow.
 *
 * @see AbstractStaticOAuthEndpoint for providers where the client_id is pre-configured.
 */
public abstract class AbstractDcrOAuthEndpoint extends AbstractMcpConnectionEndpoint {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected AbstractDcrOAuthEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
    }

    protected abstract String getMcpUrl();
    protected abstract String getRedirectUri();
    protected abstract String getProviderLabel();
    protected abstract void storePendingOAuth(String email, String state, String codeVerifier, String clientId, String tokenEndpoint);
    protected abstract Optional<PendingOAuthState> validatePendingState(String email, String state);
    protected abstract void storeToken(String email, String accessToken, String refreshToken, Instant expiresAt, String state);
    protected abstract void clearConnection(String email);
    protected abstract Optional<String> fetchAccessToken(String email);
    protected abstract RemoteMcpClient createMcpClient();

    protected void recordConnectionEvent(String email, String direction, String detail) {
        componentClient
                .forEventSourcedEntity(UUID.randomUUID().toString())
                .method(McpInteractionEntity::record)
                .invoke(new McpInteractionEntity.RecordCommand(
                        email, getProviderLabel().toLowerCase(), "oauth", Map.of(), direction, detail));
    }

    /** Outcome of the RFC 9728 discovery probe: the resource_metadata URL if found, plus
     *  human-readable diagnostics describing what each probe returned (for the error response). */
    protected record ProbeResult(String resourceMetadataUrl, String diagnostics) {
        boolean found() { return resourceMetadataUrl != null; }
    }

    /** Send a single JSON-RPC probe to the MCP server, returning the raw HTTP response. */
    protected static java.net.http.HttpResponse<String> sendProbe(HttpClient httpClient, String mcpUrl, String jsonRpcBody) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(mcpUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonRpcBody))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    /**
     * RFC 9728 discovery: find the resource_metadata URL advertised by the MCP server's 401
     * challenge. Servers differ in which method they gate: some challenge on {@code initialize},
     * others (e.g. Reo) leave {@code initialize}/{@code tools/list} open (HTTP 200) and only
     * challenge on the protected operation, {@code tools/call}. So we probe {@code initialize}
     * first and, if it carries no challenge, escalate to {@code tools/call}.
     */
    protected static ProbeResult discoverResourceMetadata(HttpClient httpClient, String mcpUrl) throws Exception {
        String initProbe = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":1," +
                "\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}," +
                "\"clientInfo\":{\"name\":\"mcp-gateway\",\"version\":\"1.0\"}}}";
        var initResp = sendProbe(httpClient, mcpUrl, initProbe);
        String initWww = initResp.headers().firstValue("WWW-Authenticate").orElse("");
        String url = extractResourceMetadata(initWww);
        if (url != null) {
            return new ProbeResult(url, "initialize → HTTP " + initResp.statusCode());
        }

        String callProbe = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":1," +
                "\"params\":{\"name\":\"__mcp_gateway_auth_probe__\",\"arguments\":{}}}";
        var callResp = sendProbe(httpClient, mcpUrl, callProbe);
        String callWww = callResp.headers().firstValue("WWW-Authenticate").orElse("");
        url = extractResourceMetadata(callWww);
        String diagnostics =
                "initialize → HTTP " + initResp.statusCode() + " WWW-Authenticate: [" + initWww + "]; "
                + "tools/call → HTTP " + callResp.statusCode() + " WWW-Authenticate: [" + callWww + "]"
                + (url == null ? ", body: " + callResp.body() : "");
        return new ProbeResult(url, diagnostics);
    }

    @Get("/connect")
    public HttpResponse connect() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        if (getMcpUrl().isBlank()) {
            return HttpResponses.internalServerError(getProviderLabel() + " MCP URL is not configured.");
        }
        if (getRedirectUri().isBlank()) {
            return HttpResponses.internalServerError(getProviderLabel() + " redirect URI is not configured.");
        }

        recordConnectionEvent(session.email(), "connect-attempt", null);

        try {
            var httpClient = HttpClient.newHttpClient();
            log.debug("{} OAuth: starting discovery from {}", getProviderLabel(), getMcpUrl());

            // Step 1: discover the RFC 9728 resource_metadata URL from the server's 401 challenge.
            var probe = discoverResourceMetadata(httpClient, getMcpUrl());
            log.debug("{} OAuth step 1: {}", getProviderLabel(), probe.diagnostics());
            if (!probe.found()) {
                return HttpResponses.internalServerError(
                        "[Step 1] MCP probe found no OAuth challenge. " + probe.diagnostics());
            }
            String resourceMetadataUrl = probe.resourceMetadataUrl();
            log.debug("{} OAuth step 1 OK: resource_metadata={}", getProviderLabel(), resourceMetadataUrl);

            // Step 2: fetch Protected Resource Metadata → authorization server URL
            var prmResp = httpClient.send(
                    HttpRequest.newBuilder().uri(URI.create(resourceMetadataUrl)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            log.debug("{} OAuth step 2: fetching PRM from {}", getProviderLabel(), resourceMetadataUrl);
            if (prmResp.statusCode() != 200) {
                return HttpResponses.internalServerError(
                        "[Step 2] PRM fetch returned HTTP " + prmResp.statusCode() + ": " + prmResp.body());
            }
            log.debug("{} OAuth step 2 response: {}", getProviderLabel(), prmResp.body());
            var prmJson = MAPPER.readTree(prmResp.body());
            var authServers = prmJson.path("authorization_servers");
            if (!authServers.isArray() || authServers.isEmpty()) {
                return HttpResponses.internalServerError(
                        "[Step 2] No authorization_servers in PRM: " + prmResp.body());
            }
            String authServerUrl = authServers.get(0).asText();
            log.debug("{} OAuth step 2 OK: auth_server={}", getProviderLabel(), authServerUrl);

            // Step 3: fetch Authorization Server Metadata (RFC 8414)
            String asmUrl = buildAuthServerMetadataUrl(authServerUrl);
            var asmResp = httpClient.send(
                    HttpRequest.newBuilder().uri(URI.create(asmUrl)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            log.debug("{} OAuth step 3: fetching auth server metadata from {}", getProviderLabel(), asmUrl);
            if (asmResp.statusCode() != 200) {
                return HttpResponses.internalServerError(
                        "[Step 3] Auth server metadata fetch returned HTTP " + asmResp.statusCode()
                        + " from " + asmUrl + ": " + asmResp.body());
            }
            log.debug("{} OAuth step 3 response: {}", getProviderLabel(), asmResp.body());
            var asmJson = MAPPER.readTree(asmResp.body());
            String authorizationEndpoint = asmJson.path("authorization_endpoint").asText();
            String tokenEndpoint = asmJson.path("token_endpoint").asText();
            String registrationEndpoint = asmJson.path("registration_endpoint").asText("");

            if (registrationEndpoint.isBlank()) {
                return HttpResponses.internalServerError(
                        "[Step 3] Authorization server metadata has no registration_endpoint: " + asmResp.body());
            }
            log.debug("{} OAuth step 3 OK: authorization_endpoint={} token_endpoint={} registration_endpoint={}",
                    getProviderLabel(), authorizationEndpoint, tokenEndpoint, registrationEndpoint);

            // Step 4: Dynamic Client Registration — no client secret
            var dcrBody = MAPPER.writeValueAsString(Map.of(
                    "client_name", getProviderLabel() + " MCP Client",
                    "redirect_uris", List.of(getRedirectUri()),
                    "grant_types", List.of("authorization_code"),
                    "response_types", List.of("code"),
                    "token_endpoint_auth_method", "none"
            ));
            var dcrResp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(registrationEndpoint))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(dcrBody))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            log.debug("{} OAuth step 4: DCR to {} body={}", getProviderLabel(), registrationEndpoint, dcrBody);
            log.debug("{} OAuth step 4 response: HTTP {} {}", getProviderLabel(), dcrResp.statusCode(), dcrResp.body());
            var dcrJson = MAPPER.readTree(dcrResp.body());
            String dynClientId = dcrJson.path("client_id").asText();
            if (dynClientId.isBlank()) {
                return HttpResponses.internalServerError(
                        "[Step 4] DCR returned HTTP " + dcrResp.statusCode() + ": " + dcrResp.body());
            }
            log.debug("{} OAuth step 4 OK: client_id={}", getProviderLabel(), dynClientId);

            // Step 5: generate PKCE pair + store pending state
            String codeVerifier = generateCodeVerifier();
            String codeChallenge = generateCodeChallenge(codeVerifier);
            String state = UUID.randomUUID().toString();

            storePendingOAuth(session.email(), state, codeVerifier, dynClientId, tokenEndpoint);

            // Step 6: redirect user to authorization endpoint
            String authorizeUrl = authorizationEndpoint
                    + "?response_type=code"
                    + "&client_id=" + encode(dynClientId)
                    + "&redirect_uri=" + encode(getRedirectUri())
                    + "&state=" + encode(state)
                    + "&code_challenge=" + encode(codeChallenge)
                    + "&code_challenge_method=S256";

            return HttpResponse.create()
                    .withStatus(StatusCodes.FOUND)
                    .addHeader(Location.create(authorizeUrl));

        } catch (Exception e) {
            recordConnectionEvent(session.email(), "connect-failed", "OAuth discovery failed: " + e.getMessage());
            return HttpResponse.create()
                    .withStatus(StatusCodes.INTERNAL_SERVER_ERROR)
                    .withEntity(akka.http.javadsl.model.ContentTypes.TEXT_PLAIN_UTF8,
                            "OAuth discovery failed: " + e.getMessage());
        }
    }

    @Get("/callback")
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
                    + "&code_verifier=" + encode(p.codeVerifier());

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
            String accessToken = tokenJson.path("access_token").asText();
            String refreshToken = tokenJson.path("refresh_token").asText("");
            long expiresIn = tokenJson.path("expires_in").asLong(3600);
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

    @Get("/disconnect")
    public HttpResponse disconnect() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        clearConnection(session.email());
        recordConnectionEvent(session.email(), "disconnect", null);
        return HttpResponse.create()
                .withStatus(StatusCodes.FOUND)
                .addHeader(Location.create("/"));
    }

    @Get("/token")
    public HttpResponse token() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        return fetchAccessToken(session.email())
                .map(t -> HttpResponses.ok(new TokenResult(t)))
                .orElse(HttpResponses.badRequest("Not connected"));
    }

    @Get("/test")
    public HttpResponse test() {
        var session = requireSession();
        if (session == null) return redirectToLogin();
        log.info("[{}] Test Connection requested by {}", getProviderLabel(), session.email());
        return HttpResponses.ok(testMcpClient(createMcpClient(), session.email(), getProviderLabel()));
    }
}
