package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class GoogleWorkspaceConnectionEntityTest {

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
}
