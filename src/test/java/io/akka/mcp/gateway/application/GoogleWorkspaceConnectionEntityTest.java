package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class GoogleWorkspaceConnectionEntityTest {

    private HttpServer tokenServer;
    private final AtomicReference<String> lastRefreshRequest = new AtomicReference<>();

    @AfterEach
    public void stopTokenServer() {
        if (tokenServer != null) tokenServer.stop(0);
    }

    /** Start a stub token endpoint that answers every refresh with the given status + body. */
    private String startTokenServer(int status, String body) throws IOException {
        tokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tokenServer.createContext("/token", exchange -> {
            lastRefreshRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        tokenServer.start();
        return "http://127.0.0.1:" + tokenServer.getAddress().getPort() + "/token";
    }

    /** Connect with an already-expired access token so the next getAccessToken() must refresh. */
    private static void connectWithExpiredToken(
            KeyValueEntityTestKit<io.akka.mcp.gateway.domain.GoogleWorkspaceConnection, GoogleWorkspaceConnectionEntity> testKit,
            String tokenEndpoint, String refreshToken) {
        testKit.method(GoogleWorkspaceConnectionEntity::initiatePkceOAuth).invoke(
                new GoogleWorkspaceConnectionEntity.InitiateCommand("state", "verifier", "client-id", tokenEndpoint));
        testKit.method(GoogleWorkspaceConnectionEntity::storeToken).invoke(
                new GoogleWorkspaceConnectionEntity.StoreTokenCommand(
                        "old-access", refreshToken, Instant.now().minusSeconds(10), "state"));
    }

    private static KeyValueEntityTestKit<io.akka.mcp.gateway.domain.GoogleWorkspaceConnection, GoogleWorkspaceConnectionEntity> newTestKit() {
        return KeyValueEntityTestKit.of("user@lightbend.com", () -> new GoogleWorkspaceConnectionEntity(
                ConfigFactory.empty().withFallback(ConfigFactory.parseString("google-workspace.client-secret = \"\""))));
    }

    @Test
    public void getStatus_whenEmpty_returnsDisconnectedState() {
        var testKit = newTestKit();

        var result = testKit.method(GoogleWorkspaceConnectionEntity::getStatus).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply().isConnected()).isFalse();
    }

    @Test
    public void initiatePkceOAuth_storesPendingState() {
        var testKit = newTestKit();
        var cmd = new GoogleWorkspaceConnectionEntity.InitiateCommand(
                "my-state", "my-verifier", "my-client-id", "https://oauth2.googleapis.com/token");

        var result = testKit.method(GoogleWorkspaceConnectionEntity::initiatePkceOAuth).invoke(cmd);

        assertThat(result.isReply()).isTrue();
        var state = testKit.getState();
        assertThat(state.pendingState()).isEqualTo("my-state");
        assertThat(state.codeVerifier()).isEqualTo("my-verifier");
        assertThat(state.clientId()).isEqualTo("my-client-id");
        assertThat(state.tokenEndpoint()).isEqualTo("https://oauth2.googleapis.com/token");
        assertThat(state.pendingExpiresAt()).isNotNull();
    }

    @Test
    public void storeToken_withValidState_storesToken() {
        var testKit = newTestKit();
        testKit.method(GoogleWorkspaceConnectionEntity::initiatePkceOAuth).invoke(
                new GoogleWorkspaceConnectionEntity.InitiateCommand(
                        "valid-state", "verifier", "client-id", "https://token.example.com"));

        var storeCmd = new GoogleWorkspaceConnectionEntity.StoreTokenCommand(
                "access-token-123", "refresh-token-abc", Instant.now().plusSeconds(3600), "valid-state");
        var result = testKit.method(GoogleWorkspaceConnectionEntity::storeToken).invoke(storeCmd);

        assertThat(result.isReply()).isTrue();
        var state = testKit.getState();
        assertThat(state.accessToken()).isEqualTo("access-token-123");
        assertThat(state.refreshToken()).isEqualTo("refresh-token-abc");
        assertThat(state.isConnected()).isTrue();
        assertThat(state.pendingState()).isNull();
    }

    @Test
    public void storeToken_withInvalidState_returnsError() {
        var testKit = newTestKit();
        testKit.method(GoogleWorkspaceConnectionEntity::initiatePkceOAuth).invoke(
                new GoogleWorkspaceConnectionEntity.InitiateCommand(
                        "valid-state", "verifier", "client-id", "https://token.example.com"));

        var storeCmd = new GoogleWorkspaceConnectionEntity.StoreTokenCommand(
                "access-token-123", "refresh-token-abc", Instant.now().plusSeconds(3600), "wrong-state");
        var result = testKit.method(GoogleWorkspaceConnectionEntity::storeToken).invoke(storeCmd);

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("Invalid");
    }

    @Test
    public void storeToken_withNoPendingState_returnsError() {
        var testKit = newTestKit();
        var storeCmd = new GoogleWorkspaceConnectionEntity.StoreTokenCommand(
                "access-token-123", "refresh-token-abc", Instant.now().plusSeconds(3600), "any-state");
        var result = testKit.method(GoogleWorkspaceConnectionEntity::storeToken).invoke(storeCmd);

        assertThat(result.isError()).isTrue();
    }

    @Test
    public void getAccessToken_whenConnectedAndNotExpired_returnsToken() {
        var testKit = newTestKit();
        testKit.method(GoogleWorkspaceConnectionEntity::initiatePkceOAuth).invoke(
                new GoogleWorkspaceConnectionEntity.InitiateCommand(
                        "state", "verifier", "client-id", "https://token.example.com"));
        testKit.method(GoogleWorkspaceConnectionEntity::storeToken).invoke(
                new GoogleWorkspaceConnectionEntity.StoreTokenCommand(
                        "access-token-123", "refresh-token-abc", Instant.now().plusSeconds(3600), "state"));

        var result = testKit.method(GoogleWorkspaceConnectionEntity::getAccessToken).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply()).isEqualTo("access-token-123");
    }

    @Test
    public void getAccessToken_whenNotConnected_returnsError() {
        var testKit = newTestKit();

        var result = testKit.method(GoogleWorkspaceConnectionEntity::getAccessToken).invoke();

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("not connected");
    }

    @Test
    public void disconnect_clearsAllState() {
        var testKit = newTestKit();
        testKit.method(GoogleWorkspaceConnectionEntity::initiatePkceOAuth).invoke(
                new GoogleWorkspaceConnectionEntity.InitiateCommand(
                        "state", "verifier", "client-id", "https://token.example.com"));
        testKit.method(GoogleWorkspaceConnectionEntity::storeToken).invoke(
                new GoogleWorkspaceConnectionEntity.StoreTokenCommand(
                        "access-token-123", "refresh-token-abc", Instant.now().plusSeconds(3600), "state"));

        var result = testKit.method(GoogleWorkspaceConnectionEntity::disconnect).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().isConnected()).isFalse();
        assertThat(testKit.getState().accessToken()).isNull();
    }

    @Test
    public void getAccessToken_whenExpired_refreshesAndStoresNewToken() throws Exception {
        var endpoint = startTokenServer(200,
                "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":1800}");
        var testKit = newTestKit();
        connectWithExpiredToken(testKit, endpoint, "old-refresh");

        var result = testKit.method(GoogleWorkspaceConnectionEntity::getAccessToken).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply()).isEqualTo("new-access");
        var state = testKit.getState();
        assertThat(state.accessToken()).isEqualTo("new-access");
        assertThat(state.refreshToken()).isEqualTo("new-refresh");
        assertThat(state.tokenExpiresAt()).isBetween(Instant.now().plusSeconds(1700), Instant.now().plusSeconds(1800));
        assertThat(lastRefreshRequest.get())
                .contains("grant_type=refresh_token")
                .contains("refresh_token=old-refresh")
                .contains("client_id=client-id")
                .doesNotContain("client_secret");
    }

    @Test
    public void getAccessToken_whenRefreshOmitsRefreshTokenAndExpiry_keepsOldRefreshTokenAndDefaultsExpiry() throws Exception {
        var endpoint = startTokenServer(200, "{\"access_token\":\"new-access\"}");
        var testKit = newTestKit();
        connectWithExpiredToken(testKit, endpoint, "old-refresh");

        var result = testKit.method(GoogleWorkspaceConnectionEntity::getAccessToken).invoke();

        assertThat(result.isReply()).isTrue();
        var state = testKit.getState();
        assertThat(state.refreshToken()).isEqualTo("old-refresh");
        assertThat(state.tokenExpiresAt()).isBetween(Instant.now().plusSeconds(3500), Instant.now().plusSeconds(3600));
    }

    @Test
    public void getAccessToken_whenRefreshReturnsNon200_returnsErrorAndKeepsState() throws Exception {
        var endpoint = startTokenServer(400, "{\"error\":\"invalid_grant\"}");
        var testKit = newTestKit();
        connectWithExpiredToken(testKit, endpoint, "old-refresh");

        var result = testKit.method(GoogleWorkspaceConnectionEntity::getAccessToken).invoke();

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("Failed to refresh").contains("HTTP 400").contains("invalid_grant");
        assertThat(testKit.getState().accessToken()).isEqualTo("old-access");
    }

    @Test
    public void getAccessToken_whenRefreshReturnsMalformedJson_returnsError() throws Exception {
        var endpoint = startTokenServer(200, "not json");
        var testKit = newTestKit();
        connectWithExpiredToken(testKit, endpoint, "old-refresh");

        var result = testKit.method(GoogleWorkspaceConnectionEntity::getAccessToken).invoke();

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("Failed to refresh");
        assertThat(testKit.getState().accessToken()).isEqualTo("old-access");
    }

    @Test
    public void getAccessToken_whenRefreshResponseHasNoAccessToken_returnsError() throws Exception {
        var endpoint = startTokenServer(200, "{}");
        var testKit = newTestKit();
        connectWithExpiredToken(testKit, endpoint, "old-refresh");

        var result = testKit.method(GoogleWorkspaceConnectionEntity::getAccessToken).invoke();

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("no access_token");
        assertThat(testKit.getState().accessToken()).isEqualTo("old-access");
    }

    @Test
    public void getAccessToken_whenExpiredWithoutRefreshToken_asksToReconnect() {
        var testKit = newTestKit();
        connectWithExpiredToken(testKit, "https://token.example.com", null);

        var result = testKit.method(GoogleWorkspaceConnectionEntity::getAccessToken).invoke();

        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("Please reconnect");
    }
}
