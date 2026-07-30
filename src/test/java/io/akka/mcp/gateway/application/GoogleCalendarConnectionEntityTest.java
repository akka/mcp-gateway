package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import com.typesafe.config.ConfigFactory;
import io.akka.mcp.gateway.domain.GoogleCalendarConnection;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class GoogleCalendarConnectionEntityTest {

    private static KeyValueEntityTestKit<GoogleCalendarConnection, GoogleCalendarConnectionEntity> testKit() {
        return KeyValueEntityTestKit.of("user@example.com", () ->
                new GoogleCalendarConnectionEntity(ConfigFactory.parseString("google-calendar.oauth.client-secret = \"\"")));
    }

    @Test
    public void getStatus_whenEmpty_returnsDisconnected() {
        var result = testKit().method(GoogleCalendarConnectionEntity::getStatus).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply().isConnected()).isFalse();
    }

    @Test
    public void initiatePkceOAuth_storesPendingState() {
        var kit = testKit();
        var cmd = new GoogleCalendarConnectionEntity.InitiateCommand("state-1", "verifier-1", "client-1");
        var result = kit.method(GoogleCalendarConnectionEntity::initiatePkceOAuth).invoke(cmd);
        assertThat(result.isReply()).isTrue();
        assertThat(kit.getState().pendingState()).isEqualTo("state-1");
        assertThat(kit.getState().codeVerifier()).isEqualTo("verifier-1");
        assertThat(kit.getState().clientId()).isEqualTo("client-1");
        assertThat(kit.getState().pendingExpiresAt()).isNotNull();
    }

    @Test
    public void storeToken_withValidState_storesTokenAndClearsPending() {
        var kit = testKit();
        kit.method(GoogleCalendarConnectionEntity::initiatePkceOAuth).invoke(
                new GoogleCalendarConnectionEntity.InitiateCommand("valid-state", "verifier", "client-id"));
        var result = kit.method(GoogleCalendarConnectionEntity::storeToken).invoke(
                new GoogleCalendarConnectionEntity.StoreTokenCommand("access-123", "refresh-456", Instant.now().plusSeconds(3600), "valid-state"));
        assertThat(result.isReply()).isTrue();
        assertThat(kit.getState().accessToken()).isEqualTo("access-123");
        assertThat(kit.getState().refreshToken()).isEqualTo("refresh-456");
        assertThat(kit.getState().isConnected()).isTrue();
        assertThat(kit.getState().pendingState()).isNull();
    }

    @Test
    public void storeToken_withInvalidState_returnsError() {
        var kit = testKit();
        kit.method(GoogleCalendarConnectionEntity::initiatePkceOAuth).invoke(
                new GoogleCalendarConnectionEntity.InitiateCommand("valid-state", "verifier", "client-id"));
        var result = kit.method(GoogleCalendarConnectionEntity::storeToken).invoke(
                new GoogleCalendarConnectionEntity.StoreTokenCommand("access-123", "refresh-456", Instant.now().plusSeconds(3600), "wrong-state"));
        assertThat(result.isError()).isTrue();
        assertThat(kit.getState().accessToken()).isNull();
    }

    @Test
    public void storeToken_withNoPendingState_returnsError() {
        var kit = testKit();
        var result = kit.method(GoogleCalendarConnectionEntity::storeToken).invoke(
                new GoogleCalendarConnectionEntity.StoreTokenCommand("access-123", "refresh-456", Instant.now().plusSeconds(3600), "any-state"));
        assertThat(result.isError()).isTrue();
    }

    @Test
    public void getAccessToken_whenConnectedAndNotExpired_returnsToken() {
        var kit = testKit();
        kit.method(GoogleCalendarConnectionEntity::initiatePkceOAuth).invoke(
                new GoogleCalendarConnectionEntity.InitiateCommand("state", "verifier", "client-id"));
        kit.method(GoogleCalendarConnectionEntity::storeToken).invoke(
                new GoogleCalendarConnectionEntity.StoreTokenCommand("access-123", "refresh-456", Instant.now().plusSeconds(3600), "state"));
        var result = kit.method(GoogleCalendarConnectionEntity::getAccessToken).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply()).isEqualTo("access-123");
    }

    @Test
    public void getAccessToken_whenNotConnected_returnsError() {
        var result = testKit().method(GoogleCalendarConnectionEntity::getAccessToken).invoke();
        assertThat(result.isError()).isTrue();
        assertThat(result.getError()).contains("not connected");
    }

    @Test
    public void disconnect_clearsState() {
        var kit = testKit();
        kit.method(GoogleCalendarConnectionEntity::initiatePkceOAuth).invoke(
                new GoogleCalendarConnectionEntity.InitiateCommand("state", "verifier", "client-id"));
        kit.method(GoogleCalendarConnectionEntity::storeToken).invoke(
                new GoogleCalendarConnectionEntity.StoreTokenCommand("access-123", "refresh-456", Instant.now().plusSeconds(3600), "state"));
        var result = kit.method(GoogleCalendarConnectionEntity::disconnect).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(kit.getState().isConnected()).isFalse();
        assertThat(kit.getState().accessToken()).isNull();
    }
}
