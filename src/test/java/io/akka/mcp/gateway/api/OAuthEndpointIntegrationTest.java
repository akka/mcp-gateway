package io.akka.mcp.gateway.api;

import akka.javasdk.testkit.TestKitSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class OAuthEndpointIntegrationTest extends TestKitSupport {

    @Test
    public void index_withoutSession_redirectsToLogin() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/").responseBodyAs(String.class).invoke()
        );
        assertThat(ex.getMessage()).contains("302");
    }

    @Test
    public void oauthStatus_withoutSession_redirectsToLogin() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/oauth/status").responseBodyAs(String.class).invoke()
        );
        assertThat(ex.getMessage()).contains("302");
    }

    @Test
    public void zohoStatus_withoutSession_redirectsToLogin() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/zoho/oauth/status").responseBodyAs(String.class).invoke()
        );
        assertThat(ex.getMessage()).contains("302");
    }

    @Test
    public void salesforceTest_withoutSession_redirectsToLogin() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/salesforce/oauth/test").responseBodyAs(String.class).invoke()
        );
        assertThat(ex.getMessage()).contains("302");
    }

    @Test
    public void loginPage_isPublic() {
        var response = httpClient.GET("/login").responseBodyAs(String.class).invoke();
        assertThat(response.status().isSuccess()).isTrue();
    }
}
