package io.akka.mcp.gateway.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import io.akka.mcp.gateway.testsupport.TokenMcpFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** An OAuth connector without an {@code mcp-url} is off: it cannot start or complete a sign-in, and nothing is redirected. */
public class TokenMcpOAuthNotSetUpIntegrationTest extends TestKitSupport {

    private static final String APP_ID = "oauth-not-set-up-app-id";

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig(TokenMcpFixtures.oauthSetting("", APP_ID));
    }

    private String signedInUser() {
        return GatewayFixtures.browserSession(componentClient, "oauth-off-" + System.nanoTime() + "@lightbend.com", List.of(),
                List.of(new UserSession.App(APP_ID, TokenMcpFixtures.NAME)));
    }

    @Test
    public void startingASignIn_whenTheConnectorIsNotSetUp_isRefused() {
        var response = httpClient.GET(TokenMcpFixtures.oauthPath(TokenMcpFixtures.ID + "/connect")).addHeader("Cookie", "SESSION=" + signedInUser()).invoke();

        assertThat(response.status().intValue()).isEqualTo(503);
        assertThat(response.httpResponse().getHeader("Location")).isEmpty();
    }

    @Test
    public void aCallbackForAConnectorThatIsNotSetUp_isRefused() {
        var response = httpClient.GET("/token-mcp-oauth/callback?code=abc&state=" + TokenMcpFixtures.ID + ".x")
                .addHeader("Cookie", "SESSION=" + signedInUser()).invoke();

        assertThat(response.status().intValue()).isEqualTo(503);
    }
}
