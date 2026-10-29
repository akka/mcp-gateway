package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.StatusCodes;
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
    public void login_afterSignOut_showsSignedOutPageWithoutScript() {
        var response = httpClient.GET("/login?flash=logout").responseBodyAs(String.class).invoke();

        assertThat(response.status().isSuccess()).isTrue();
        assertThat(response.body()).contains("You have been signed out.").contains("href=\"/auth/start\"")
                .doesNotContain("<script");
    }

    @Test
    public void login_withoutSession_redirectsServerSideToOktaSignIn() {
        var response = httpClient.GET("/login").invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        assertThat(response.httpResponse().getHeader("Location").map(h -> h.value())).hasValue("/auth/start");
    }

    @Test
    public void login_withReturnTo_passesItOnToOktaSignIn() {
        var response = httpClient.GET("/login?return_to=%2Finteractions%3Fx%3D1").invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        assertThat(response.httpResponse().getHeader("Location").map(h -> h.value()))
                .hasValue("/auth/start?return_to=%2Finteractions%3Fx%3D1");
    }

    @Test
    public void dashboard_withoutSession_redirectsToLoginWithReturnPath() {
        var response = httpClient.GET("/").invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        assertThat(response.httpResponse().getHeader("Location").map(h -> h.value()))
                .hasValue("/login?return_to=%2F");
    }

    @Test
    public void safeReturnTo_keepsLocalPaths() {
        assertThat(AuthEndpoint.safeReturnTo("/interactions")).isEqualTo("/interactions");
        assertThat(AuthEndpoint.safeReturnTo("/admin/write-access?x=1")).isEqualTo("/admin/write-access?x=1");
    }

    @Test
    public void safeReturnTo_rejectsAnythingThatCouldLeaveTheGateway() {
        assertThat(AuthEndpoint.safeReturnTo(null)).isEqualTo("/");
        assertThat(AuthEndpoint.safeReturnTo("https://evil.example")).isEqualTo("/");
        assertThat(AuthEndpoint.safeReturnTo("//evil.example")).isEqualTo("/");
        assertThat(AuthEndpoint.safeReturnTo("/\\evil.example")).isEqualTo("/");
        assertThat(AuthEndpoint.safeReturnTo("/x\r\nSet-Cookie: a=b")).isEqualTo("/");
        assertThat(AuthEndpoint.safeReturnTo("/login")).isEqualTo("/");
        assertThat(AuthEndpoint.safeReturnTo("/auth/logout")).isEqualTo("/");
    }

    @Test
    public void safeReturnTo_rejectsPathsThatAreNotValidUris() {
        assertThat(AuthEndpoint.safeReturnTo("/interactions with space")).isEqualTo("/");
        assertThat(AuthEndpoint.safeReturnTo("/x?q=<script>")).isEqualTo("/");
        assertThat(AuthEndpoint.safeReturnTo("/100%")).isEqualTo("/");
    }

    @Test
    public void safeReturnTo_keepsPercentEncodedPaths() {
        assertThat(AuthEndpoint.safeReturnTo("/interactions?q=a%20b")).isEqualTo("/interactions?q=a%20b");
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
    public void logout_withoutSession_redirectsToSignedOutLoginPage() {
        var response = httpClient.GET("/auth/logout").invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        assertThat(response.httpResponse().getHeader("Location").map(h -> h.value()))
                .hasValue("/login?flash=logout");
    }

    @Test
    public void permissions_withoutSession_redirectsToLoginWithReturnPath() {
        var response = httpClient.GET("/auth/permissions").invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        assertThat(response.httpResponse().getHeader("Location").map(h -> h.value()))
                .hasValue("/login?return_to=%2Fauth%2Fpermissions");
    }


    @Test
    public void me_withoutSession_returns401() {
        var ex = assertThrows(Exception.class, () ->
                httpClient.GET("/auth/me").responseBodyAs(String.class).invoke());
        assertThat(ex.getMessage()).contains("401");
    }

    @Test
    public void oktaStatusPage_withoutSession_redirectsToLoginWithReturnPath() {
        var response = httpClient.GET("/okta-status").invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        assertThat(response.httpResponse().getHeader("Location").map(h -> h.value()))
                .hasValue("/login?return_to=%2Fokta-status");
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
                .addHeader("Cookie", "SESSION=" + token)
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
                .addHeader("Cookie", "SESSION=" + token)
                .responseBodyAs(String.class)
                .invoke();

        assertThat(response.status().isSuccess()).isTrue();
    }

    @Test
    public void permissions_withNonAdminSession_returns200() {
        var token = createSession("test@lightbend.com", "Test User", List.of());

        var response = httpClient.GET("/auth/permissions")
                .addHeader("Cookie", "SESSION=" + token)
                .responseBodyAs(String.class)
                .invoke();

        assertThat(response.status().isSuccess()).isTrue();
    }

    @Test
    public void login_whenAlreadyLoggedIn_redirectsToDashboard() {
        var token = createSession("test@lightbend.com", "Test User", List.of());

        var response = httpClient.GET("/login")
                .addHeader("Cookie", "SESSION=" + token)
                .invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        assertThat(response.httpResponse().getHeader("Location").map(h -> h.value()))
                .hasValue("/?flash=already-logged-in");
    }

    @Test
    public void logout_withSession_endsTheGatewaySessionOnly() {
        var token = createSession("test@lightbend.com", "Test User", List.of());

        var response = httpClient.GET("/auth/logout")
                .addHeader("Cookie", "SESSION=" + token)
                .invoke();

        // Stays on the gateway: no detour through Okta's logout, so the Okta session survives.
        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        assertThat(response.httpResponse().getHeader("Location").map(h -> h.value()))
                .hasValue("/login?flash=logout");
        var me = assertThrows(Exception.class, () ->
                httpClient.GET("/auth/me").addHeader("Cookie", "SESSION=" + token)
                        .responseBodyAs(String.class).invoke());
        assertThat(me.getMessage()).contains("401");
    }
}
