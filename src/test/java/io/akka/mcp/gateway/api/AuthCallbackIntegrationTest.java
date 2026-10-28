package io.akka.mcp.gateway.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.OAuthClientEntity;
import io.akka.mcp.gateway.testsupport.FakeOkta;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the whole Okta sign-in against {@link FakeOkta}: {@code /auth/start} (or the MCP client's
 * {@code /oauth2/authorize}) to Okta and back through {@code /auth/callback}. The callback is where
 * the email domain is enforced, on the identity Okta vouched for.
 */
public class AuthCallbackIntegrationTest extends TestKitSupport {

    private static final FakeOkta okta = FakeOkta.start();
    static {
        okta.respond("/.well-known/openid-configuration", 200, """
                {"authorization_endpoint":"%1$s/authorize","token_endpoint":"%1$s/token","userinfo_endpoint":"%1$s/userinfo"}
                """.formatted(okta.url()));
        okta.respond("/token", 200, "{\"access_token\":\"fake-access-token\",\"id_token\":\"\"}");
    }
    // Never follows redirects, so each hop of the sign-in can be asserted on.
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                okta.allowed-email-domain = "lightbend.com"
                okta.issuer-url = "%s"
                okta.client-id = "gateway"
                okta.api-token = ""
                okta.redirect-uri = "http://localhost/auth/callback"
                """.formatted(okta.url()));
    }

    @AfterAll
    static void stopOkta() {
        okta.close();
    }

    @Test
    public void callback_withOtherDomainEmail_returns403AndCreatesNoSession() throws Exception {
        signingInAs("someone@example.com");

        var response = callback(stateFrom(get("/auth/start")));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    public void callback_withLookalikeDomainEmail_returns403() throws Exception {
        signingInAs("someone@notlightbend.com");

        var response = callback(stateFrom(get("/auth/start")));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    public void callback_withBlankEmail_returns403AndCreatesNoSession() throws Exception {
        signingInAs("");

        var response = callback(stateFrom(get("/auth/start")));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    public void callback_withAllowedEmail_landsOnReturnToWithAWorkingSession() throws Exception {
        signingInAs("someone@lightbend.com");

        var response = callback(stateFrom(get("/auth/start?return_to=" + encode("/interactions?x=1"))));

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue("/interactions?x=1");
        var me = get("/auth/me", sessionCookie(response));
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("someone@lightbend.com");
    }

    @Test
    public void callback_withoutReturnTo_landsOnDashboard() throws Exception {
        signingInAs("someone@lightbend.com");

        var response = callback(stateFrom(get("/auth/start")));

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue("/");
    }

    @Test
    public void mcpClientFlow_withOtherDomainEmail_returns403AndCreatesNoSession() throws Exception {
        signingInAs("someone@example.com");

        var response = callback(stateFrom(mcpAuthorize()));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    public void mcpClientFlow_withAllowedEmail_continuesToConsent() throws Exception {
        signingInAs("someone@lightbend.com");

        String state = stateFrom(mcpAuthorize());
        var response = callback(state);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue("/oauth2/consent?oauth_state=" + state);
        assertThat(sessionCookie(response)).startsWith("SESSION=");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Sets the email Okta's userinfo reports for the next sign-in. */
    private static void signingInAs(String email) {
        okta.respond("/userinfo", 200, "{\"email\":\"" + email + "\",\"name\":\"Fake User\"}");
    }

    /** An MCP client's authorize request with no gateway session, which goes through Okta. */
    private HttpResponse<String> mcpAuthorize() throws Exception {
        String clientId = UUID.randomUUID().toString();
        String redirectUri = "http://127.0.0.1:1234/callback";
        componentClient.forKeyValueEntity(clientId)
                .method(OAuthClientEntity::register)
                .invoke(new OAuthClientEntity.RegisterCommand(clientId, "Test Client", redirectUri));
        return get("/oauth2/authorize?response_type=code&client_id=" + clientId
                + "&redirect_uri=" + encode(redirectUri)
                + "&code_challenge=" + McpOAuthEndpoint.pkceChallenge("test-verifier")
                + "&code_challenge_method=S256&state=client-state");
    }

    /** The {@code state} the gateway sent to Okta, read from the redirect to the fake authorize URL. */
    private static String stateFrom(HttpResponse<String> redirectToOkta) {
        assertThat(redirectToOkta.statusCode()).isEqualTo(302);
        var location = URI.create(redirectToOkta.headers().firstValue("Location").orElseThrow());
        assertThat(location.toString()).startsWith(okta.url() + "/authorize");
        for (var param : location.getRawQuery().split("&")) {
            if (param.startsWith("state=")) {
                return java.net.URLDecoder.decode(param.substring("state=".length()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("No state in " + location);
    }

    private HttpResponse<String> callback(String state) throws Exception {
        return get("/auth/callback?code=fake-code&state=" + encode(state));
    }

    private static String sessionCookie(HttpResponse<String> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(path, null);
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        var builder = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + testKit.getPort() + path)).GET();
        if (cookie != null) builder.header("Cookie", cookie);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
