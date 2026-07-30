package io.akka.mcp.gateway.api;

import akka.javasdk.testkit.TestKitSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class OAuthMetadataEndpointIntegrationTest extends TestKitSupport {

    @Test
    public void oauthProtectedResource_returnsMetadata() {
        var response = httpClient.GET("/.well-known/oauth-protected-resource")
                .responseBodyAs(OAuthMetadataEndpoint.ProtectedResourceMetadata.class)
                .invoke();

        assertThat(response.status().isSuccess()).isTrue();
        var body = response.body();
        assertThat(body.resource()).isNotBlank();
        assertThat(body.authorization_servers()).isNotEmpty();
        assertThat(body.scopes_supported()).contains("mcp:read");
        assertThat(body.bearer_methods_supported()).contains("header");
    }

    @Test
    public void oauthAuthorizationServer_returnsMetadata() {
        var response = httpClient.GET("/.well-known/oauth-authorization-server")
                .responseBodyAs(OAuthMetadataEndpoint.AuthorizationServerMetadata.class)
                .invoke();

        assertThat(response.status().isSuccess()).isTrue();
        var body = response.body();
        assertThat(body.issuer()).isNotBlank();
        assertThat(body.authorization_endpoint()).contains("/oauth2/authorize");
        assertThat(body.token_endpoint()).contains("/oauth2/token");
        assertThat(body.registration_endpoint()).contains("/oauth2/register");
        assertThat(body.response_types_supported()).contains("code");
        assertThat(body.grant_types_supported()).contains("authorization_code", "refresh_token");
        assertThat(body.code_challenge_methods_supported()).contains("S256");
    }

    @Test
    public void oauthAuthorizationServer_endpointsPointToSameBase() {
        var response = httpClient.GET("/.well-known/oauth-authorization-server")
                .responseBodyAs(OAuthMetadataEndpoint.AuthorizationServerMetadata.class)
                .invoke();

        var body = response.body();
        assertThat(body.authorization_endpoint()).startsWith(body.issuer());
        assertThat(body.token_endpoint()).startsWith(body.issuer());
        assertThat(body.registration_endpoint()).startsWith(body.issuer());
    }
}
