package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class OAuthAuthorizationCodeEntityTest {

    private static OAuthAuthorizationCodeEntity.CreateCommand createCmd(Instant expiresAt) {
        return new OAuthAuthorizationCodeEntity.CreateCommand(
                "code-abc", "client-1", "session-token-xyz",
                "http://localhost/callback", "challenge-hash", "S256",
                "mcp:read", expiresAt);
    }

    @Test
    public void create_storesAuthorizationCode() {
        var testKit = KeyValueEntityTestKit.of("code-abc", OAuthAuthorizationCodeEntity::new);
        var result = testKit.method(OAuthAuthorizationCodeEntity::create)
                .invoke(createCmd(Instant.now().plusSeconds(600)));

        assertThat(result.isReply()).isTrue();
        var state = testKit.getState();
        assertThat(state.code()).isEqualTo("code-abc");
        assertThat(state.clientId()).isEqualTo("client-1");
        assertThat(state.sessionToken()).isEqualTo("session-token-xyz");
        assertThat(state.redirectUri()).isEqualTo("http://localhost/callback");
        assertThat(state.codeChallenge()).isEqualTo("challenge-hash");
        assertThat(state.codeChallengeMethod()).isEqualTo("S256");
        assertThat(state.scope()).isEqualTo("mcp:read");
        assertThat(state.used()).isFalse();
        assertThat(state.isEmpty()).isFalse();
    }

    @Test
    public void get_whenEmpty_returnsEmptyState() {
        var testKit = KeyValueEntityTestKit.of("code-abc", OAuthAuthorizationCodeEntity::new);
        var result = testKit.method(OAuthAuthorizationCodeEntity::get).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply().isEmpty()).isTrue();
    }

    @Test
    public void get_afterCreate_returnsStoredCode() {
        var testKit = KeyValueEntityTestKit.of("code-abc", OAuthAuthorizationCodeEntity::new);
        testKit.method(OAuthAuthorizationCodeEntity::create)
                .invoke(createCmd(Instant.now().plusSeconds(600)));

        var result = testKit.method(OAuthAuthorizationCodeEntity::get).invoke();
        assertThat(result.getReply().code()).isEqualTo("code-abc");
        assertThat(result.getReply().used()).isFalse();
    }

    @Test
    public void markUsed_setsUsedFlag() {
        var testKit = KeyValueEntityTestKit.of("code-abc", OAuthAuthorizationCodeEntity::new);
        testKit.method(OAuthAuthorizationCodeEntity::create)
                .invoke(createCmd(Instant.now().plusSeconds(600)));

        var result = testKit.method(OAuthAuthorizationCodeEntity::markUsed).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().used()).isTrue();
    }

    @Test
    public void isExpired_whenExpiresAtIsInPast_returnsTrue() {
        var testKit = KeyValueEntityTestKit.of("code-abc", OAuthAuthorizationCodeEntity::new);
        testKit.method(OAuthAuthorizationCodeEntity::create)
                .invoke(createCmd(Instant.now().minusSeconds(1)));

        assertThat(testKit.getState().isExpired()).isTrue();
    }

    @Test
    public void isExpired_whenExpiresAtIsInFuture_returnsFalse() {
        var testKit = KeyValueEntityTestKit.of("code-abc", OAuthAuthorizationCodeEntity::new);
        testKit.method(OAuthAuthorizationCodeEntity::create)
                .invoke(createCmd(Instant.now().plusSeconds(600)));

        assertThat(testKit.getState().isExpired()).isFalse();
    }
}
