package io.akka.mcp.gateway.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.UserSessionEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class AuthEndpointIntegrationTest extends TestKitSupport {

    @Override
    protected TestKit.Settings testKitSettings() {
        // Group names and allowed email domain are config-driven; set them so the tests are
        // deterministic without depending on the (deliberately empty) checked-in defaults.
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                okta.allowed-email-domain = "lightbend.com"
                okta.groups.admin = "mcp-gateway-admin"
                okta.groups.reader = "mcp-gateway-reader"
                okta.groups.writer = "mcp-gateway-writer"
                """);
    }

    // ── unauthenticated tests ────────────────────────────────────────────────

    @Test
    public void loginPage_isPublic() {
        var response = httpClient.GET("/login").responseBodyAs(String.class).invoke();
        assertThat(response.status().isSuccess()).isTrue();
    }

    @Test
    public void initiate_withNonLightbendEmail_returns400() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.POST("/auth/initiate")
                        .withRequestBody(new AuthEndpoint.InitiateRequest("user@example.com"))
                        .responseBodyAs(String.class).invoke());
        assertThat(ex.getMessage()).contains("400");
    }

    @Test
    public void callback_withMissingParams_returns400() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/auth/callback").responseBodyAs(String.class).invoke());
        assertThat(ex.getMessage()).contains("400");
    }

    @Test
    public void callback_withErrorParam_returns400() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/auth/callback?error=access_denied&error_description=User+denied")
                        .responseBodyAs(String.class).invoke());
        assertThat(ex.getMessage()).contains("400");
    }

    @Test
    public void callback_withUnknownState_returns400() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/auth/callback?code=some-code&state=unknown-state-" + UUID.randomUUID())
                        .responseBodyAs(String.class).invoke());
        assertThat(ex.getMessage()).contains("400");
    }

    @Test
    public void logout_withoutSession_redirects() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/auth/logout").responseBodyAs(String.class).invoke());
        assertThat(ex.getMessage()).contains("302");
    }

    @Test
    public void permissions_withoutSession_redirectsToLogin() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/auth/permissions").responseBodyAs(String.class).invoke());
        assertThat(ex.getMessage()).contains("302");
    }


    @Test
    public void me_withoutSession_returns401() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/auth/me").responseBodyAs(String.class).invoke());
        assertThat(ex.getMessage()).contains("401");
    }

    @Test
    public void oktaStatusPage_withoutSession_redirectsToLogin() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/okta-status").responseBodyAs(String.class).invoke());
        assertThat(ex.getMessage()).contains("302");
    }

    @Test
    public void oktaStatus_withoutSession_returns401() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/auth/okta-status").responseBodyAs(String.class).invoke());
        assertThat(ex.getMessage()).contains("401");
    }

    // ── authenticated tests ──────────────────────────────────────────────────

    private String createSession(String email, String displayName, List<String> groups) {
        var token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(UserSessionEntity::create)
                .invoke(new UserSessionEntity.CreateCommand(
                        email, displayName, Instant.now().plusSeconds(3600), groups, "", List.of()));
        return token;
    }

    @Test
    public void me_withSession_returnsUserInfo() {
        var token = createSession("test@lightbend.com", "Test User", List.of("mcp-users"));

        var response = httpClient.GET("/auth/me")
                .addHeader("Authorization", "Bearer " + token)
                .responseBodyAs(AuthEndpoint.MeResponse.class)
                .invoke();

        assertThat(response.status().isSuccess()).isTrue();
        assertThat(response.body().email()).isEqualTo("test@lightbend.com");
        assertThat(response.body().displayName()).isEqualTo("Test User");
        assertThat(response.body().groups()).containsExactly("mcp-users");
    }


    @Test
    public void permissions_withAdminSession_returns200() {
        var token = createSession("test@lightbend.com", "Test User", List.of("mcp-gateway-admin"));

        var response = httpClient.GET("/auth/permissions")
                .addHeader("Authorization", "Bearer " + token)
                .responseBodyAs(String.class)
                .invoke();

        assertThat(response.status().isSuccess()).isTrue();
    }

    @Test
    public void permissions_withNonAdminSession_returns200() {
        var token = createSession("test@lightbend.com", "Test User", List.of());

        var response = httpClient.GET("/auth/permissions")
                .addHeader("Authorization", "Bearer " + token)
                .responseBodyAs(String.class)
                .invoke();

        assertThat(response.status().isSuccess()).isTrue();
    }

    @Test
    public void login_whenAlreadyLoggedIn_redirectsToDashboard() {
        var token = createSession("test@lightbend.com", "Test User", List.of());

        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/login")
                        .addHeader("Cookie", "SESSION=" + token)
                        .responseBodyAs(String.class).invoke());
        assertThat(ex.getMessage()).contains("302");
    }

    @Test
    public void logout_withSession_clearsSessionAndRedirects() {
        var token = createSession("test@lightbend.com", "Test User", List.of());

        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/auth/logout")
                        .addHeader("Cookie", "SESSION=" + token)
                        .responseBodyAs(String.class).invoke());
        assertThat(ex.getMessage()).contains("302");
    }
}
