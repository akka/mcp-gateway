package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import io.akka.mcp.gateway.domain.ReoConnection;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class ReoConnectionEntityTest {

    private static KeyValueEntityTestKit<ReoConnection, ReoConnectionEntity> testKit() {
        return KeyValueEntityTestKit.of("user@example.com", ReoConnectionEntity::new);
    }

    @Test
    public void getStatus_whenEmpty_returnsDisconnected() {
        var result = testKit().method(ReoConnectionEntity::getStatus).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply().isConnected()).isFalse();
    }

    @Test
    public void initiatePkceOAuth_storesPendingState() {
        var kit = testKit();
        var cmd = new ReoConnectionEntity.InitiateCommand("state-1", "verifier-1", "client-1", "https://auth.example.com/token");
        var result = kit.method(ReoConnectionEntity::initiatePkceOAuth).invoke(cmd);
        assertThat(result.isReply()).isTrue();
        assertThat(kit.getState().pendingState()).isEqualTo("state-1");
        assertThat(kit.getState().codeVerifier()).isEqualTo("verifier-1");
        assertThat(kit.getState().clientId()).isEqualTo("client-1");
        assertThat(kit.getState().tokenEndpoint()).isEqualTo("https://auth.example.com/token");
        assertThat(kit.getState().pendingExpiresAt()).isNotNull();
    }

    @Test
    public void storeToken_withValidState_storesTokenAndClearsPending() {
        var kit = testKit();
        kit.method(ReoConnectionEntity::initiatePkceOAuth).invoke(
                new ReoConnectionEntity.InitiateCommand("valid-state", "verifier", "client-id", "https://auth.example.com/token"));
        var result = kit.method(ReoConnectionEntity::storeToken).invoke(
                new ReoConnectionEntity.StoreTokenCommand("access-123", "refresh-456", Instant.now().plusSeconds(3600), "valid-state"));
        assertThat(result.isReply()).isTrue();
        assertThat(kit.getState().accessToken()).isEqualTo("access-123");
        assertThat(kit.getState().isConnected()).isTrue();
        assertThat(kit.getState().pendingState()).isNull();
    }

    @Test
    public void storeToken_withInvalidState_returnsError() {
        var kit = testKit();
        kit.method(ReoConnectionEntity::initiatePkceOAuth).invoke(
                new ReoConnectionEntity.InitiateCommand("valid-state", "verifier", "client-id", "https://auth.example.com/token"));
        var result = kit.method(ReoConnectionEntity::storeToken).invoke(
                new ReoConnectionEntity.StoreTokenCommand("access-123", "refresh-456", Instant.now().plusSeconds(3600), "wrong-state"));
        assertThat(result.isError()).isTrue();
        assertThat(kit.getState().accessToken()).isNull();
    }

    @Test
    public void storeToken_withNoPendingState_returnsError() {
        var kit = testKit();
        var result = kit.method(ReoConnectionEntity::storeToken).invoke(
                new ReoConnectionEntity.StoreTokenCommand("access-123", "refresh-456", Instant.now().plusSeconds(3600), "any-state"));
        assertThat(result.isError()).isTrue();
    }

    @Test
    public void getAccessToken_whenConnectedAndNotExpired_returnsToken() {
        var kit = testKit();
        kit.method(ReoConnectionEntity::initiatePkceOAuth).invoke(
                new ReoConnectionEntity.InitiateCommand("state", "verifier", "client-id", "https://auth.example.com/token"));
        kit.method(ReoConnectionEntity::storeToken).invoke(
                new ReoConnectionEntity.StoreTokenCommand("access-123", "refresh-456", Instant.now().plusSeconds(3600), "state"));
        var result = kit.method(ReoConnectionEntity::getAccessToken).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply()).isEqualTo("access-123");
    }

    @Test
    public void getAccessToken_whenNotConnected_returnsError() {
        var result = testKit().method(ReoConnectionEntity::getAccessToken).invoke();
        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("not connected");
    }

    @Test
    public void disconnect_clearsState() {
        var kit = testKit();
        kit.method(ReoConnectionEntity::initiatePkceOAuth).invoke(
                new ReoConnectionEntity.InitiateCommand("state", "verifier", "client-id", "https://auth.example.com/token"));
        kit.method(ReoConnectionEntity::storeToken).invoke(
                new ReoConnectionEntity.StoreTokenCommand("access-123", "refresh-456", Instant.now().plusSeconds(3600), "state"));
        var result = kit.method(ReoConnectionEntity::disconnect).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(kit.getState().isConnected()).isFalse();
        assertThat(kit.getState().accessToken()).isNull();
    }
}
