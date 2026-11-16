package io.akka.mcp.gateway.application;

import akka.http.javadsl.model.ContentType;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.MediaTypes;
import akka.javasdk.http.HttpClient;
import akka.javasdk.http.HttpClientProvider;
import akka.javasdk.http.RequestBuilder;
import akka.util.ByteString;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.mcp.gateway.domain.OAuthClientRegistration;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The OAuth sign-in one MCP server offers (RFC 9728 discovery, RFC 8414 metadata, RFC 7591 dynamic client
 * registration, PKCE, RFC 8707 resource). Every address it advertises must be https, or loopback http when the
 * connector itself is loopback http. A failure carries no text the server sent, so nothing it said can reach a user or a log.
 */
public class McpOAuthFlow {

    public static final class FlowException extends Exception {
        private final boolean clientRejected;

        private FlowException(String message) {
            this(message, false);
        }

        private FlowException(String message, boolean clientRejected) {
            super(message);
            this.clientRejected = clientRejected;
        }

        /** The token endpoint said it does not know the client, so the registration is no use any more. */
        public boolean clientRejected() {
            return clientRejected;
        }
    }

    private record Answer(int status, Optional<String> wwwAuthenticate, String body) {}

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern RESOURCE_METADATA = Pattern.compile("resource_metadata=(?:\"([^\"]+)\"|([^\\s,]+))");
    private static final String PROTECTED_RESOURCE_WELL_KNOWN = "/.well-known/oauth-protected-resource";
    private static final String AUTH_SERVER_WELL_KNOWN = "/.well-known/oauth-authorization-server";
    private static final String OPENID_WELL_KNOWN = "/.well-known/openid-configuration";
    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":1,"
            + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"mcp-gateway\",\"version\":\"1.0\"}}}";
    private static final ContentType FORM = ContentTypes.create(MediaTypes.APPLICATION_X_WWW_FORM_URLENCODED);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpClientProvider httpClientProvider;
    private final String mcpEndpointUrl;
    private final boolean loopbackHttpAllowed;

    /** {@code loopbackHttpAllowed} is for a connector that is itself on loopback http, which only tests and local runs use. */
    public McpOAuthFlow(HttpClientProvider httpClientProvider, String mcpEndpointUrl, boolean loopbackHttpAllowed) {
        this.httpClientProvider = httpClientProvider;
        this.mcpEndpointUrl = mcpEndpointUrl;
        this.loopbackHttpAllowed = loopbackHttpAllowed;
    }

    /** The address of the MCP server's endpoint, which tokens are issued for and sent to, and nowhere else. */
    public String resource() {
        return mcpEndpointUrl;
    }

    /** Finds the server's sign-in and registers the gateway as a client of it. */
    public OAuthClientRegistration register(String redirectUri, String clientName) throws FlowException {
        var resourceMetadata = firstAnswer(resourceMetadataUrls(), this::describesThisServer);
        var authServer = authorizationServerOf(resourceMetadata);
        var serverMetadata = firstAnswer(authServerMetadataUrls(authServer), metadata -> hasIssuer(metadata, authServer));
        requireS256(serverMetadata);
        var authorizationEndpoint = allowed(serverMetadata.path("authorization_endpoint").asText(""));
        var tokenEndpoint = allowed(serverMetadata.path("token_endpoint").asText(""));
        var registrationEndpoint = allowed(serverMetadata.path("registration_endpoint").asText(""));
        var scope = scopeOf(resourceMetadata.path("scopes_supported"), serverMetadata.path("scopes_supported"));
        var clientId = registerClient(registrationEndpoint, redirectUri, clientName, scope);
        return new OAuthClientRegistration(clientId, authorizationEndpoint, tokenEndpoint, scope, redirectUri, mcpEndpointUrl, Instant.now());
    }

    public String authorizeUrl(OAuthClientRegistration registration, String state, String codeVerifier) {
        var endpoint = registration.authorizationEndpoint();
        return endpoint + (endpoint.contains("?") ? "&" : "?")
                + "response_type=code"
                + "&client_id=" + OAuthGrant.encode(registration.clientId())
                + "&redirect_uri=" + OAuthGrant.encode(registration.redirectUri())
                + "&state=" + OAuthGrant.encode(state)
                + "&code_challenge=" + OAuthGrant.encode(codeChallenge(codeVerifier))
                + "&code_challenge_method=S256"
                + "&resource=" + OAuthGrant.encode(mcpEndpointUrl)
                + (registration.scope().isEmpty() ? "" : "&scope=" + OAuthGrant.encode(registration.scope()));
    }

    public static String newCodeVerifier() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public OAuthGrant.Issued exchangeCode(String tokenEndpoint, String clientId, String redirectUri, String code, String codeVerifier)
            throws FlowException {
        var answer = post(allowed(tokenEndpoint), FORM, "grant_type=authorization_code"
                + "&code=" + OAuthGrant.encode(code)
                + "&client_id=" + OAuthGrant.encode(clientId)
                + "&redirect_uri=" + OAuthGrant.encode(redirectUri)
                + "&code_verifier=" + OAuthGrant.encode(codeVerifier)
                + "&resource=" + OAuthGrant.encode(mcpEndpointUrl));
        if (answer.status() != 200) {
            throw new FlowException("The token request was refused with HTTP " + answer.status(), clientIsUnknown(answer));
        }
        return OAuthGrant.issuedFrom(answer.body(), Optional.empty()).orElseThrow(() -> new FlowException("The token response has no access token"));
    }

    /** https, or loopback http for a loopback connector, with no credentials or fragment; a query is fine. */
    static boolean isAllowedEndpoint(String url, boolean loopbackHttpAllowed) {
        try {
            var uri = URI.create(url);
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) return false;
            return "https".equals(uri.getScheme())
                    || (loopbackHttpAllowed && "http".equals(uri.getScheme()) && TokenMcpConfig.LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String codeChallenge(String verifier) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** What the server's challenge names, if it names anything, then the well-known addresses for its endpoint and for its origin. */
    private List<String> resourceMetadataUrls() {
        var urls = new ArrayList<String>();
        challengedResourceMetadata().ifPresent(urls::add);
        var uri = URI.create(mcpEndpointUrl);
        urls.add(originOf(uri) + PROTECTED_RESOURCE_WELL_KNOWN + uri.getRawPath());
        urls.add(originOf(uri) + PROTECTED_RESOURCE_WELL_KNOWN);
        return urls;
    }

    /** The address the server's own challenge names; a server that does not answer the probe may still publish the well-known addresses. */
    private Optional<String> challengedResourceMetadata() {
        try {
            var challenge = post(mcpEndpointUrl, ContentTypes.APPLICATION_JSON, INITIALIZE);
            var matcher = RESOURCE_METADATA.matcher(challenge.wwwAuthenticate().orElse(""));
            return matcher.find() ? Optional.of(matcher.group(1) != null ? matcher.group(1) : matcher.group(2)) : Optional.empty();
        } catch (FlowException e) {
            return Optional.empty();
        }
    }

    private static List<String> authServerMetadataUrls(String issuer) {
        var uri = URI.create(issuer);
        var path = uri.getRawPath() == null ? "" : trimmed(uri.getRawPath());
        return List.of(originOf(uri) + AUTH_SERVER_WELL_KNOWN + path, originOf(uri) + path + OPENID_WELL_KNOWN);
    }

    /** RFC 9728: the metadata must name this server's address or the address it lives under, so a server cannot hand over another resource's sign-in. */
    private boolean describesThisServer(JsonNode resourceMetadata) {
        var resource = trimmed(resourceMetadata.path("resource").asText(""));
        var endpoint = trimmed(mcpEndpointUrl);
        return !resource.isEmpty() && (endpoint.equals(resource) || endpoint.startsWith(resource + "/"));
    }

    private String authorizationServerOf(JsonNode resourceMetadata) throws FlowException {
        var servers = resourceMetadata.path("authorization_servers");
        if (!servers.isArray() || servers.isEmpty() || !servers.get(0).isTextual()) throw new FlowException("The server names no authorization server");
        return allowed(servers.get(0).asText());
    }

    private static boolean hasIssuer(JsonNode serverMetadata, String authServer) {
        return trimmed(serverMetadata.path("issuer").asText("")).equals(trimmed(authServer));
    }

    private static void requireS256(JsonNode serverMetadata) throws FlowException {
        var supported = serverMetadata.path("code_challenge_methods_supported");
        if (StreamSupport.stream(supported.spliterator(), false).noneMatch(method -> "S256".equals(method.asText()))) {
            throw new FlowException("The authorization server does not offer PKCE with S256");
        }
    }

    private String registerClient(String registrationEndpoint, String redirectUri, String clientName, String scope) throws FlowException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("client_name", clientName);
        body.putArray("redirect_uris").add(redirectUri);
        body.putArray("grant_types").add("authorization_code").add("refresh_token");
        body.putArray("response_types").add("code");
        body.put("token_endpoint_auth_method", "none");
        if (!scope.isEmpty()) body.put("scope", scope);
        var answer = post(registrationEndpoint, ContentTypes.APPLICATION_JSON, body.toString());
        var clientId = answer.status() / 100 == 2 ? parse(answer.body()).path("client_id").asText("") : "";
        if (clientId.isEmpty()) throw new FlowException("Client registration was refused with HTTP " + answer.status());
        return clientId;
    }

    /** The first of these addresses that answers with acceptable JSON; one that is not allowed, does not answer or is not acceptable is skipped. */
    private JsonNode firstAnswer(List<String> urls, Predicate<JsonNode> acceptable) throws FlowException {
        for (var url : urls) {
            if (!isAllowedEndpoint(url, loopbackHttpAllowed)) continue;
            try {
                var answer = get(url);
                if (answer.status() == 200) {
                    var metadata = parse(answer.body());
                    if (acceptable.test(metadata)) return metadata;
                }
            } catch (FlowException skipped) {
                // the next address may answer
            }
        }
        throw new FlowException("The server publishes no usable metadata at the addresses tried");
    }

    private Answer get(String url) throws FlowException {
        return send(url, client -> client.GET(pathOf(url)));
    }

    private Answer post(String url, ContentType contentType, String body) throws FlowException {
        return send(url, client -> client.POST(pathOf(url)).withRequestBody(contentType, body.getBytes(StandardCharsets.UTF_8)));
    }

    /** One request with a timeout. Redirects are not followed, so a credential is never sent anywhere else. */
    private Answer send(String url, Function<HttpClient, RequestBuilder<ByteString>> request) throws FlowException {
        try {
            var client = httpClientProvider.httpClientFor(originOf(URI.create(url)));
            var response = request.apply(client).withTimeout(TIMEOUT).addHeader("Accept", "application/json, text/event-stream").invoke();
            return new Answer(response.status().intValue(),
                    response.httpResponse().getHeader("WWW-Authenticate").map(HttpHeader::value),
                    response.body().utf8String());
        } catch (Exception e) {
            throw new FlowException("The server could not be reached (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static boolean clientIsUnknown(Answer answer) {
        return OAuthGrant.errorCode(answer.status(), answer.body()).filter("invalid_client"::equals).isPresent();
    }

    private static JsonNode parse(String body) throws FlowException {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new FlowException("The server's answer was not JSON");
        }
    }

    private static String scopeOf(JsonNode... advertisements) {
        return Stream.of(advertisements)
                .filter(JsonNode::isArray)
                .map(scopes -> StreamSupport.stream(scopes.spliterator(), false)
                        .filter(JsonNode::isTextual).map(JsonNode::asText).filter(scope -> !scope.isBlank()).toList())
                .filter(scopes -> !scopes.isEmpty())
                .findFirst()
                .map(scopes -> String.join(" ", scopes))
                .orElse("");
    }

    private String allowed(String url) throws FlowException {
        if (!isAllowedEndpoint(url, loopbackHttpAllowed)) throw new FlowException("The server advertised an address that is missing or not https");
        return url;
    }

    private static String originOf(URI uri) {
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    private static String pathOf(String url) {
        var uri = URI.create(url);
        return (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }

    private static String trimmed(String url) {
        return url.replaceAll("/+$", "");
    }
}
