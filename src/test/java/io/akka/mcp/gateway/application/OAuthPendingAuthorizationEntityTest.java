package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class OAuthPendingAuthorizationEntityTest {

    private static OAuthPendingAuthorizationEntity.CreateCommand createCmd(Instant expiresAt) {
        return new OAuthPendingAuthorizationEntity.CreateCommand(
                "client-1", "http://localhost/callback", "challenge-hash",
                "S256", "mcp:read", "client-state-abc", expiresAt);
    }

    @Test
    public void create_storesPendingAuthorization() {
        var testKit = KeyValueEntityTestKit.of("oauth-state-1", OAuthPendingAuthorizationEntity::new);
        var result = testKit.method(OAuthPendingAuthorizationEntity::create)
                .invoke(createCmd(Instant.now().plusSeconds(600)));

        assertThat(result.isReply()).isTrue();
        var state = testKit.getState();
        assertThat(state.clientId()).isEqualTo("client-1");
        assertThat(state.redirectUri()).isEqualTo("http://localhost/callback");
        assertThat(state.codeChallenge()).isEqualTo("challenge-hash");
        assertThat(state.codeChallengeMethod()).isEqualTo("S256");
        assertThat(state.scope()).isEqualTo("mcp:read");
        assertThat(state.clientState()).isEqualTo("client-state-abc");
        assertThat(state.isEmpty()).isFalse();
    }

    @Test
    public void get_whenEmpty_returnsEmptyState() {
        var testKit = KeyValueEntityTestKit.of("oauth-state-1", OAuthPendingAuthorizationEntity::new);

        var result = testKit.method(OAuthPendingAuthorizationEntity::get).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply().isEmpty()).isTrue();
    }

    @Test
    public void get_afterCreate_returnsPendingAuthorization() {
        var testKit = KeyValueEntityTestKit.of("oauth-state-1", OAuthPendingAuthorizationEntity::new);
        testKit.method(OAuthPendingAuthorizationEntity::create)
                .invoke(createCmd(Instant.now().plusSeconds(600)));

        var result = testKit.method(OAuthPendingAuthorizationEntity::get).invoke();

        assertThat(result.getReply().clientId()).isEqualTo("client-1");
        assertThat(result.getReply().isEmpty()).isFalse();
    }

    @Test
    public void delete_clearsState() {
        var testKit = KeyValueEntityTestKit.of("oauth-state-1", OAuthPendingAuthorizationEntity::new);
        testKit.method(OAuthPendingAuthorizationEntity::create)
                .invoke(createCmd(Instant.now().plusSeconds(600)));

        var result = testKit.method(OAuthPendingAuthorizationEntity::delete).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().isEmpty()).isTrue();
    }

    @Test
    public void isExpired_whenExpiresAtIsInPast_returnsTrue() {
        var testKit = KeyValueEntityTestKit.of("oauth-state-1", OAuthPendingAuthorizationEntity::new);
        testKit.method(OAuthPendingAuthorizationEntity::create)
                .invoke(createCmd(Instant.now().minusSeconds(1)));

        assertThat(testKit.getState().isExpired()).isTrue();
    }

    @Test
    public void isExpired_whenExpiresAtIsInFuture_returnsFalse() {
        var testKit = KeyValueEntityTestKit.of("oauth-state-1", OAuthPendingAuthorizationEntity::new);
        testKit.method(OAuthPendingAuthorizationEntity::create)
                .invoke(createCmd(Instant.now().plusSeconds(600)));

        assertThat(testKit.getState().isExpired()).isFalse();
    }
}
