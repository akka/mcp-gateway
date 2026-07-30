package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class SalesforceConnectionEntityTest {

    private static final String TOKEN_ENDPOINT = "https://login.salesforce.com/services/oauth2/token";

    private SalesforceConnectionEntity.InitiateCommand initiateCmd(String state) {
        return new SalesforceConnectionEntity.InitiateCommand(state, "verifier-abc", "client-123", TOKEN_ENDPOINT);
    }

    @Test
    public void initiatePkceOAuth_storesState() {
        var testKit = KeyValueEntityTestKit.of("user@example.com", () -> new SalesforceConnectionEntity(ConfigFactory.empty().withFallback(ConfigFactory.parseString("salesforce.oauth.client-secret = \"\""))));
        var result = testKit.method(SalesforceConnectionEntity::initiatePkceOAuth).invoke(initiateCmd("my-state"));
        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().pendingState()).isEqualTo("my-state");
        assertThat(testKit.getState().codeVerifier()).isEqualTo("verifier-abc");
        assertThat(testKit.getState().pendingExpiresAt()).isNotNull();
    }

    @Test
    public void initiatePkceOAuth_overwritesPreviousState() {
        var testKit = KeyValueEntityTestKit.of("user@example.com", () -> new SalesforceConnectionEntity(ConfigFactory.empty().withFallback(ConfigFactory.parseString("salesforce.oauth.client-secret = \"\""))));
        testKit.method(SalesforceConnectionEntity::initiatePkceOAuth).invoke(initiateCmd("state-1"));
        testKit.method(SalesforceConnectionEntity::initiatePkceOAuth).invoke(initiateCmd("state-2"));
        assertThat(testKit.getState().pendingState()).isEqualTo("state-2");
    }

    @Test
    public void storeToken_validState_storesTokenAndClearsPending() {
        var testKit = KeyValueEntityTestKit.of("user@example.com", () -> new SalesforceConnectionEntity(ConfigFactory.empty().withFallback(ConfigFactory.parseString("salesforce.oauth.client-secret = \"\""))));
        testKit.method(SalesforceConnectionEntity::initiatePkceOAuth).invoke(initiateCmd("valid-state"));
        var cmd = new SalesforceConnectionEntity.StoreTokenCommand(
                "access-token-123", "refresh-token-456", Instant.now().plusSeconds(7200), "valid-state");
        var result = testKit.method(SalesforceConnectionEntity::storeToken).invoke(cmd);
        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().accessToken()).isEqualTo("access-token-123");
        assertThat(testKit.getState().refreshToken()).isEqualTo("refresh-token-456");
        assertThat(testKit.getState().pendingState()).isNull();
        assertThat(testKit.getState().isConnected()).isTrue();
    }

    @Test
    public void storeToken_invalidState_returnsError() {
        var testKit = KeyValueEntityTestKit.of("user@example.com", () -> new SalesforceConnectionEntity(ConfigFactory.empty().withFallback(ConfigFactory.parseString("salesforce.oauth.client-secret = \"\""))));
        testKit.method(SalesforceConnectionEntity::initiatePkceOAuth).invoke(initiateCmd("valid-state"));
        var cmd = new SalesforceConnectionEntity.StoreTokenCommand(
                "access-token-123", null, Instant.now().plusSeconds(7200), "wrong-state");
        var result = testKit.method(SalesforceConnectionEntity::storeToken).invoke(cmd);
        assertThat(result.isError()).isTrue();
        assertThat(testKit.getState().accessToken()).isNull();
    }

    @Test
    public void storeToken_expiredState_returnsError() {
        var testKit = KeyValueEntityTestKit.of("user@example.com", () -> new SalesforceConnectionEntity(ConfigFactory.empty().withFallback(ConfigFactory.parseString("salesforce.oauth.client-secret = \"\""))));
        var cmd = new SalesforceConnectionEntity.StoreTokenCommand(
                "access-token-123", null, Instant.now().plusSeconds(7200), "any-state");
        var result = testKit.method(SalesforceConnectionEntity::storeToken).invoke(cmd);
        assertThat(result.isError()).isTrue();
    }

    @Test
    public void disconnect_clearsToken() {
        var testKit = KeyValueEntityTestKit.of("user@example.com", () -> new SalesforceConnectionEntity(ConfigFactory.empty().withFallback(ConfigFactory.parseString("salesforce.oauth.client-secret = \"\""))));
        testKit.method(SalesforceConnectionEntity::initiatePkceOAuth).invoke(initiateCmd("state"));
        testKit.method(SalesforceConnectionEntity::storeToken).invoke(
                new SalesforceConnectionEntity.StoreTokenCommand(
                        "access-token-123", "refresh-token", Instant.now().plusSeconds(7200), "state"));
        var result = testKit.method(SalesforceConnectionEntity::disconnect).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().isConnected()).isFalse();
        assertThat(testKit.getState().accessToken()).isNull();
    }

    @Test
    public void getStatus_whenEmpty_returnsDisconnected() {
        var testKit = KeyValueEntityTestKit.of("user@example.com", () -> new SalesforceConnectionEntity(ConfigFactory.empty().withFallback(ConfigFactory.parseString("salesforce.oauth.client-secret = \"\""))));
        var result = testKit.method(SalesforceConnectionEntity::getStatus).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply().isConnected()).isFalse();
    }

    @Test
    public void getStatus_whenConnected_returnsToken() {
        var testKit = KeyValueEntityTestKit.of("user@example.com", () -> new SalesforceConnectionEntity(ConfigFactory.empty().withFallback(ConfigFactory.parseString("salesforce.oauth.client-secret = \"\""))));
        testKit.method(SalesforceConnectionEntity::initiatePkceOAuth).invoke(initiateCmd("state"));
        testKit.method(SalesforceConnectionEntity::storeToken).invoke(
                new SalesforceConnectionEntity.StoreTokenCommand(
                        "my-token", "my-refresh", Instant.now().plusSeconds(7200), "state"));
        var result = testKit.method(SalesforceConnectionEntity::getStatus).invoke();
        assertThat(result.getReply().accessToken()).isEqualTo("my-token");
        assertThat(result.getReply().isConnected()).isTrue();
    }

    @Test
    public void getAccessToken_whenConnectedAndNotExpired_returnsToken() {
        var testKit = KeyValueEntityTestKit.of("user@example.com", () -> new SalesforceConnectionEntity(ConfigFactory.empty().withFallback(ConfigFactory.parseString("salesforce.oauth.client-secret = \"\""))));
        testKit.method(SalesforceConnectionEntity::initiatePkceOAuth).invoke(initiateCmd("state"));
        testKit.method(SalesforceConnectionEntity::storeToken).invoke(
                new SalesforceConnectionEntity.StoreTokenCommand(
                        "my-token", "my-refresh", Instant.now().plusSeconds(7200), "state"));
        var result = testKit.method(SalesforceConnectionEntity::getAccessToken).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply()).isEqualTo("my-token");
    }

    @Test
    public void getAccessToken_whenNotConnected_returnsError() {
        var testKit = KeyValueEntityTestKit.of("user@example.com", () -> new SalesforceConnectionEntity(ConfigFactory.empty().withFallback(ConfigFactory.parseString("salesforce.oauth.client-secret = \"\""))));
        var result = testKit.method(SalesforceConnectionEntity::getAccessToken).invoke();
        assertThat(result.isError()).isTrue();
    }
}
