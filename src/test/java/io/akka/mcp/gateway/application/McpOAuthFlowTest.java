package io.akka.mcp.gateway.application;

import io.akka.mcp.gateway.domain.OAuthClientRegistration;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class McpOAuthFlowTest {

    @Test
    public void anAdvertisedAddress_mustBeHttps() {
        assertThat(McpOAuthFlow.isAllowedEndpoint("https://auth.example.com/token", false)).isTrue();
        assertThat(McpOAuthFlow.isAllowedEndpoint("http://auth.example.com/token", false)).isFalse();
        assertThat(McpOAuthFlow.isAllowedEndpoint("ftp://auth.example.com/token", false)).isFalse();
    }

    @Test
    public void anAdvertisedLoopbackAddress_isRefused_unlessTheConnectorIsItselfLoopbackHttp() {
        assertThat(McpOAuthFlow.isAllowedEndpoint("http://127.0.0.1:9000/token", false)).isFalse();
        assertThat(McpOAuthFlow.isAllowedEndpoint("http://localhost/token", false)).isFalse();
        assertThat(McpOAuthFlow.isAllowedEndpoint("http://127.0.0.1:9000/token", true)).isTrue();
        assertThat(McpOAuthFlow.isAllowedEndpoint("http://auth.example.com/token", true)).isFalse();
    }

    @Test
    public void anAdvertisedAddress_mayCarryAQuery_butNotCredentialsOrAFragment() {
        assertThat(McpOAuthFlow.isAllowedEndpoint("https://auth.example.com/authorize?tenant=x", false)).isTrue();
        assertThat(McpOAuthFlow.isAllowedEndpoint("https://user:pass@auth.example.com/token", false)).isFalse();
        assertThat(McpOAuthFlow.isAllowedEndpoint("https://auth.example.com/token#frag", false)).isFalse();
    }

    @Test
    public void aMalformedAddress_isRefused() {
        assertThat(McpOAuthFlow.isAllowedEndpoint("", false)).isFalse();
        assertThat(McpOAuthFlow.isAllowedEndpoint("not a url", false)).isFalse();
        assertThat(McpOAuthFlow.isAllowedEndpoint("https://", false)).isFalse();
    }

    @Test
    public void theCodeChallenge_isTheS256OfTheVerifier_asInRfc7636() {
        assertThat(McpOAuthFlow.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    public void theAuthorizeUrl_joinsAQueryAlreadyOnTheEndpoint_andNamesTheResourceAndScope() {
        var flow = new McpOAuthFlow(null, "https://mcp.example.com/mcp", false);
        var registration = new OAuthClientRegistration("client-1", "https://auth.example.com/authorize?tenant=x", "https://auth.example.com/token", "mcp:read",
                "https://gateway.example.com/callback", "https://mcp.example.com/mcp", Instant.now());

        var url = flow.authorizeUrl(registration, "state-1", "verifier");

        assertThat(url).startsWith("https://auth.example.com/authorize?tenant=x&response_type=code&client_id=client-1")
                .contains("&state=state-1").contains("&code_challenge_method=S256").contains("&scope=mcp%3Aread")
                .contains("&resource=https%3A%2F%2Fmcp.example.com%2Fmcp").contains("&redirect_uri=https%3A%2F%2Fgateway.example.com%2Fcallback");
    }
}
