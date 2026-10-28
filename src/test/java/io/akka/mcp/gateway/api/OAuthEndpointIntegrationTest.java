package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.testkit.TestKitSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class OAuthEndpointIntegrationTest extends TestKitSupport {

    @Test
    public void index_withoutSession_redirectsToLogin() {
        var response = httpClient.GET("/").invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        assertThat(response.httpResponse().getHeader("Location").map(h -> h.value()))
                .hasValue("/login?return_to=%2F");
    }

    @Test
    public void zohoStatus_withoutSession_redirectsToLogin() {
        var response = httpClient.GET("/zoho/oauth/status").invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        assertThat(response.httpResponse().getHeader("Location").map(h -> h.value())).hasValue("/login");
    }

    @Test
    public void salesforceTest_withoutSession_redirectsToLogin() {
        var response = httpClient.GET("/salesforce/oauth/test").invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        assertThat(response.httpResponse().getHeader("Location").map(h -> h.value())).hasValue("/login");
    }

    @Test
    public void staticOAuthConnect_withoutSession_redirectsToLoginReturningToConnect() {
        var response = httpClient.GET("/googleworkspace/oauth/connect").invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        assertThat(response.httpResponse().getHeader("Location").map(h -> h.value()))
                .hasValue("/login?return_to=%2Fgoogleworkspace%2Foauth%2Fconnect");
    }

    @Test
    public void dcrOAuthConnect_withoutSession_redirectsToLoginReturningToConnect() {
        var response = httpClient.GET("/zoho/oauth/connect").invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        assertThat(response.httpResponse().getHeader("Location").map(h -> h.value()))
                .hasValue("/login?return_to=%2Fzoho%2Foauth%2Fconnect");
    }

    @Test
    public void signedOutLoginPage_isPublic() {
        var response = httpClient.GET("/login?flash=logout").responseBodyAs(String.class).invoke();
        assertThat(response.status().isSuccess()).isTrue();
    }
}
