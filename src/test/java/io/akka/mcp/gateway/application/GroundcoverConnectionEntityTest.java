package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import io.akka.mcp.gateway.domain.GroundcoverConnection;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class GroundcoverConnectionEntityTest {

    private static KeyValueEntityTestKit<GroundcoverConnection, GroundcoverConnectionEntity> testKit() {
        return KeyValueEntityTestKit.of("user@example.com", GroundcoverConnectionEntity::new);
    }

    @Test
    public void getStatus_whenEmpty_returnsDisconnected() {
        var result = testKit().method(GroundcoverConnectionEntity::getStatus).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply().isConnected()).isFalse();
    }

    @Test
    public void initiatePkceOAuth_storesPendingState() {
        var kit = testKit();
        var cmd = new GroundcoverConnectionEntity.InitiateCommand("state-1", "verifier-1", "client-1", "https://auth.example.com/token");
        var result = kit.method(GroundcoverConnectionEntity::initiatePkceOAuth).invoke(cmd);
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
        kit.method(GroundcoverConnectionEntity::initiatePkceOAuth).invoke(
                new GroundcoverConnectionEntity.InitiateCommand("valid-state", "verifier", "client-id", "https://auth.example.com/token"));
        var result = kit.method(GroundcoverConnectionEntity::storeToken).invoke(
                new GroundcoverConnectionEntity.StoreTokenCommand("access-123", "refresh-456", Instant.now().plusSeconds(3600), "valid-state"));
        assertThat(result.isReply()).isTrue();
        assertThat(kit.getState().accessToken()).isEqualTo("access-123");
        assertThat(kit.getState().isConnected()).isTrue();
        assertThat(kit.getState().pendingState()).isNull();
    }

    @Test
    public void storeToken_withInvalidState_returnsError() {
        var kit = testKit();
        kit.method(GroundcoverConnectionEntity::initiatePkceOAuth).invoke(
                new GroundcoverConnectionEntity.InitiateCommand("valid-state", "verifier", "client-id", "https://auth.example.com/token"));
        var result = kit.method(GroundcoverConnectionEntity::storeToken).invoke(
                new GroundcoverConnectionEntity.StoreTokenCommand("access-123", "refresh-456", Instant.now().plusSeconds(3600), "wrong-state"));
        assertThat(result.isError()).isTrue();
        assertThat(kit.getState().accessToken()).isNull();
    }

    @Test
    public void storeToken_withNoPendingState_returnsError() {
        var kit = testKit();
        var result = kit.method(GroundcoverConnectionEntity::storeToken).invoke(
                new GroundcoverConnectionEntity.StoreTokenCommand("access-123", "refresh-456", Instant.now().plusSeconds(3600), "any-state"));
        assertThat(result.isError()).isTrue();
    }

    @Test
    public void getAccessToken_whenConnectedAndNotExpired_returnsToken() {
        var kit = testKit();
        kit.method(GroundcoverConnectionEntity::initiatePkceOAuth).invoke(
                new GroundcoverConnectionEntity.InitiateCommand("state", "verifier", "client-id", "https://auth.example.com/token"));
        kit.method(GroundcoverConnectionEntity::storeToken).invoke(
                new GroundcoverConnectionEntity.StoreTokenCommand("access-123", "refresh-456", Instant.now().plusSeconds(3600), "state"));
        var result = kit.method(GroundcoverConnectionEntity::getAccessToken).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply()).isEqualTo("access-123");
    }

    @Test
    public void getAccessToken_whenNotConnected_returnsError() {
        var result = testKit().method(GroundcoverConnectionEntity::getAccessToken).invoke();
        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("not connected");
    }

    @Test
    public void getAccessToken_whenExpiredWithNoRefreshToken_returnsError() {
        var kit = testKit();
        kit.method(GroundcoverConnectionEntity::initiatePkceOAuth).invoke(
                new GroundcoverConnectionEntity.InitiateCommand("state", "verifier", "client-id", "https://auth.example.com/token"));
        kit.method(GroundcoverConnectionEntity::storeToken).invoke(
                new GroundcoverConnectionEntity.StoreTokenCommand("access-123", null, Instant.now().minusSeconds(60), "state"));
        var result = kit.method(GroundcoverConnectionEntity::getAccessToken).invoke();
        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("expired");
    }

    @Test
    public void disconnect_clearsState() {
        var kit = testKit();
        kit.method(GroundcoverConnectionEntity::initiatePkceOAuth).invoke(
                new GroundcoverConnectionEntity.InitiateCommand("state", "verifier", "client-id", "https://auth.example.com/token"));
        kit.method(GroundcoverConnectionEntity::storeToken).invoke(
                new GroundcoverConnectionEntity.StoreTokenCommand("access-123", "refresh-456", Instant.now().plusSeconds(3600), "state"));
        var result = kit.method(GroundcoverConnectionEntity::disconnect).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(kit.getState().isConnected()).isFalse();
        assertThat(kit.getState().accessToken()).isNull();
    }
}
