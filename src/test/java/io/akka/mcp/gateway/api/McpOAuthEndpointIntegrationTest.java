package io.akka.mcp.gateway.api;

import akka.javasdk.testkit.TestKitSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class McpOAuthEndpointIntegrationTest extends TestKitSupport {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private HttpResponse<String> postForm(String path, String formData) throws Exception {
        int port = testKit.getPort();
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formData))
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ── Dynamic Client Registration ──────────────────────────────────────────

    @Test
    public void register_withValidRequest_returnsClientId() {
        var req = new McpOAuthEndpoint.RegisterRequest(
                List.of("http://localhost:1234/callback"), "My MCP Client");

        var response = httpClient.POST("/oauth2/register")
                .withRequestBody(req)
                .responseBodyAs(McpOAuthEndpoint.RegisterResponse.class)
                .invoke();

        assertThat(response.status().isSuccess()).isTrue();
        assertThat(response.status().intValue()).isEqualTo(201);
        var body = response.body();
        assertThat(body.client_id()).isNotBlank();
        assertThat(body.client_name()).isEqualTo("My MCP Client");
        assertThat(body.redirect_uris()).contains("http://localhost:1234/callback");
    }

    @Test
    public void register_withoutRedirectUris_returnsBadRequest() {
        var req = new McpOAuthEndpoint.RegisterRequest(List.of(), "My Client");

        var ex = assertThrows(Exception.class, () ->
                httpClient.POST("/oauth2/register")
                        .withRequestBody(req)
                        .responseBodyAs(String.class)
                        .invoke());

        assertThat(ex.getMessage()).contains("400");
    }

    @Test
    public void register_withNullClientName_usesDefaultName() {
        var req = new McpOAuthEndpoint.RegisterRequest(
                List.of("http://localhost:1234/callback"), null);

        var response = httpClient.POST("/oauth2/register")
                .withRequestBody(req)
                .responseBodyAs(McpOAuthEndpoint.RegisterResponse.class)
                .invoke();

        assertThat(response.status().intValue()).isEqualTo(201);
        assertThat(response.body().client_name()).isEqualTo("MCP Client");
    }

    // ── Token endpoint — form-encoded, tested via java.net.http.HttpClient ──

    @Test
    public void token_withUnsupportedGrantType_returnsBadRequest() throws Exception {
        var resp = postForm("/oauth2/token", "grant_type=implicit");

        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("unsupported_grant_type");
    }

    @Test
    public void token_withMissingCode_returnsBadRequest() throws Exception {
        var resp = postForm("/oauth2/token",
                "grant_type=authorization_code&redirect_uri=http%3A%2F%2Flocalhost%2Fcb&client_id=x");

        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("invalid_request");
    }

    @Test
    public void token_withInvalidAuthorizationCode_returnsInvalidGrant() throws Exception {
        var resp = postForm("/oauth2/token",
                "grant_type=authorization_code&code=nonexistent&redirect_uri=http%3A%2F%2Flocalhost%2Fcb&client_id=x&code_verifier=abc");

        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("invalid_grant");
    }

    @Test
    public void token_refreshGrant_withInvalidToken_returnsInvalidGrant() throws Exception {
        var resp = postForm("/oauth2/token",
                "grant_type=refresh_token&refresh_token=nonexistent&client_id=x");

        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.body()).contains("invalid_grant");
    }

    // ── Authorize endpoint — missing params ─────────────────────────────────

    @Test
    public void authorize_withMissingParams_returnsBadRequest() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/oauth2/authorize")
                        .responseBodyAs(String.class)
                        .invoke());

        assertThat(ex.getMessage()).contains("400");
    }

    @Test
    public void authorize_withInvalidResponseType_returnsBadRequest() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/oauth2/authorize?client_id=x&redirect_uri=http%3A%2F%2Flocalhost%2Fcb&response_type=token&code_challenge=abc&code_challenge_method=S256")
                        .responseBodyAs(String.class)
                        .invoke());

        assertThat(ex.getMessage()).contains("400");
    }

    // ── pkceChallenge helper ─────────────────────────────────────────────────

    @Test
    public void pkceChallenge_isBase64UrlEncodedSha256() {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String challenge = McpOAuthEndpoint.pkceChallenge(verifier);
        assertThat(challenge).isNotBlank();
        assertThat(challenge).doesNotContain("+", "/", "=");
    }

    @Test
    public void pkceChallenge_isDeterministic() {
        String verifier = "test-verifier-12345";
        assertThat(McpOAuthEndpoint.pkceChallenge(verifier))
                .isEqualTo(McpOAuthEndpoint.pkceChallenge(verifier));
    }
}
