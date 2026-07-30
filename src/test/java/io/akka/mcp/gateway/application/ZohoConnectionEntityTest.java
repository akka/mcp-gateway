package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class ZohoConnectionEntityTest {

    @Test
    public void initiatePkceOAuth_storesPendingState() {
        var testKit = KeyValueEntityTestKit.of("default", ZohoConnectionEntity::new);
        var cmd = new ZohoConnectionEntity.InitiateCommand(
                "my-state", "my-verifier", "my-client-id", "https://auth.example.com/token");
        var result = testKit.method(ZohoConnectionEntity::initiatePkceOAuth).invoke(cmd);
        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().pendingState()).isEqualTo("my-state");
        assertThat(testKit.getState().codeVerifier()).isEqualTo("my-verifier");
        assertThat(testKit.getState().clientId()).isEqualTo("my-client-id");
        assertThat(testKit.getState().tokenEndpoint()).isEqualTo("https://auth.example.com/token");
        assertThat(testKit.getState().pendingExpiresAt()).isNotNull();
    }

    @Test
    public void initiatePkceOAuth_overwritesPreviousState() {
        var testKit = KeyValueEntityTestKit.of("default", ZohoConnectionEntity::new);
        testKit.method(ZohoConnectionEntity::initiatePkceOAuth).invoke(
                new ZohoConnectionEntity.InitiateCommand("state-1", "verifier-1", "client-1", "https://token1"));
        testKit.method(ZohoConnectionEntity::initiatePkceOAuth).invoke(
                new ZohoConnectionEntity.InitiateCommand("state-2", "verifier-2", "client-2", "https://token2"));
        assertThat(testKit.getState().pendingState()).isEqualTo("state-2");
        assertThat(testKit.getState().codeVerifier()).isEqualTo("verifier-2");
    }

    @Test
    public void storeToken_validState_storesTokenAndClearsPending() {
        var testKit = KeyValueEntityTestKit.of("default", ZohoConnectionEntity::new);
        testKit.method(ZohoConnectionEntity::initiatePkceOAuth).invoke(
                new ZohoConnectionEntity.InitiateCommand("valid-state", "verifier", "client-id", "https://token"));
        var cmd = new ZohoConnectionEntity.StoreTokenCommand(
                "access-token-123", "refresh-token-456", Instant.now(), "valid-state");
        var result = testKit.method(ZohoConnectionEntity::storeToken).invoke(cmd);
        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().accessToken()).isEqualTo("access-token-123");
        assertThat(testKit.getState().refreshToken()).isEqualTo("refresh-token-456");
        assertThat(testKit.getState().pendingState()).isNull();
        assertThat(testKit.getState().codeVerifier()).isNull();
        assertThat(testKit.getState().isConnected()).isTrue();
    }

    @Test
    public void storeToken_invalidState_returnsError() {
        var testKit = KeyValueEntityTestKit.of("default", ZohoConnectionEntity::new);
        testKit.method(ZohoConnectionEntity::initiatePkceOAuth).invoke(
                new ZohoConnectionEntity.InitiateCommand("valid-state", "verifier", "client-id", "https://token"));
        var cmd = new ZohoConnectionEntity.StoreTokenCommand(
                "access-token-123", "refresh-token-456", Instant.now(), "wrong-state");
        var result = testKit.method(ZohoConnectionEntity::storeToken).invoke(cmd);
        assertThat(result.isError()).isTrue();
        assertThat(testKit.getState().accessToken()).isNull();
    }

    @Test
    public void storeToken_noPendingState_returnsError() {
        var testKit = KeyValueEntityTestKit.of("default", ZohoConnectionEntity::new);
        var cmd = new ZohoConnectionEntity.StoreTokenCommand(
                "access-token-123", "refresh-token-456", Instant.now(), "any-state");
        var result = testKit.method(ZohoConnectionEntity::storeToken).invoke(cmd);
        assertThat(result.isError()).isTrue();
    }

    @Test
    public void disconnect_clearsToken() {
        var testKit = KeyValueEntityTestKit.of("default", ZohoConnectionEntity::new);
        testKit.method(ZohoConnectionEntity::initiatePkceOAuth).invoke(
                new ZohoConnectionEntity.InitiateCommand("state", "verifier", "client-id", "https://token"));
        testKit.method(ZohoConnectionEntity::storeToken).invoke(
                new ZohoConnectionEntity.StoreTokenCommand("my-token", "my-refresh", Instant.now(), "state"));
        var result = testKit.method(ZohoConnectionEntity::disconnect).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().isConnected()).isFalse();
        assertThat(testKit.getState().accessToken()).isNull();
    }

    @Test
    public void getStatus_whenEmpty_returnsDisconnected() {
        var testKit = KeyValueEntityTestKit.of("default", ZohoConnectionEntity::new);
        var result = testKit.method(ZohoConnectionEntity::getStatus).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply().isConnected()).isFalse();
    }

    @Test
    public void getStatus_whenConnected_returnsToken() {
        var testKit = KeyValueEntityTestKit.of("default", ZohoConnectionEntity::new);
        testKit.method(ZohoConnectionEntity::initiatePkceOAuth).invoke(
                new ZohoConnectionEntity.InitiateCommand("state", "verifier", "client-id", "https://token"));
        testKit.method(ZohoConnectionEntity::storeToken).invoke(
                new ZohoConnectionEntity.StoreTokenCommand("my-token", "my-refresh", Instant.now(), "state"));
        var result = testKit.method(ZohoConnectionEntity::getStatus).invoke();
        assertThat(result.getReply().accessToken()).isEqualTo("my-token");
        assertThat(result.getReply().isConnected()).isTrue();
    }
}
