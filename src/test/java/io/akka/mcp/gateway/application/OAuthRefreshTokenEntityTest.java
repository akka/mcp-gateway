package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class OAuthRefreshTokenEntityTest {

    private static OAuthRefreshTokenEntity.CreateCommand createCmd(Instant expiresAt) {
        return new OAuthRefreshTokenEntity.CreateCommand(
                "token-xyz", "user@lightbend.com", "Alice",
                "client-1", List.of("group-a"), expiresAt);
    }

    @Test
    public void create_storesRefreshToken() {
        var testKit = KeyValueEntityTestKit.of("token-xyz", OAuthRefreshTokenEntity::new);
        var result = testKit.method(OAuthRefreshTokenEntity::create)
                .invoke(createCmd(Instant.now().plusSeconds(2592000)));

        assertThat(result.isReply()).isTrue();
        var state = testKit.getState();
        assertThat(state.token()).isEqualTo("token-xyz");
        assertThat(state.userId()).isEqualTo("user@lightbend.com");
        assertThat(state.displayName()).isEqualTo("Alice");
        assertThat(state.clientId()).isEqualTo("client-1");
        assertThat(state.groups()).containsExactly("group-a");
        assertThat(state.revoked()).isFalse();
        assertThat(state.isEmpty()).isFalse();
    }

    @Test
    public void get_whenEmpty_returnsEmptyState() {
        var testKit = KeyValueEntityTestKit.of("token-xyz", OAuthRefreshTokenEntity::new);

        var result = testKit.method(OAuthRefreshTokenEntity::get).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply().isEmpty()).isTrue();
    }

    @Test
    public void revoke_setsRevokedFlag() {
        var testKit = KeyValueEntityTestKit.of("token-xyz", OAuthRefreshTokenEntity::new);
        testKit.method(OAuthRefreshTokenEntity::create)
                .invoke(createCmd(Instant.now().plusSeconds(2592000)));

        var result = testKit.method(OAuthRefreshTokenEntity::revoke).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().revoked()).isTrue();
    }

    @Test
    public void revoke_whenEmpty_repliesWithoutError() {
        var testKit = KeyValueEntityTestKit.of("token-xyz", OAuthRefreshTokenEntity::new);

        var result = testKit.method(OAuthRefreshTokenEntity::revoke).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().isEmpty()).isTrue();
    }

    @Test
    public void isValid_whenActiveAndNotExpired_returnsTrue() {
        var testKit = KeyValueEntityTestKit.of("token-xyz", OAuthRefreshTokenEntity::new);
        testKit.method(OAuthRefreshTokenEntity::create)
                .invoke(createCmd(Instant.now().plusSeconds(2592000)));

        assertThat(testKit.getState().isValid()).isTrue();
    }

    @Test
    public void isValid_whenRevoked_returnsFalse() {
        var testKit = KeyValueEntityTestKit.of("token-xyz", OAuthRefreshTokenEntity::new);
        testKit.method(OAuthRefreshTokenEntity::create)
                .invoke(createCmd(Instant.now().plusSeconds(2592000)));
        testKit.method(OAuthRefreshTokenEntity::revoke).invoke();

        assertThat(testKit.getState().isValid()).isFalse();
    }

    @Test
    public void isValid_whenExpired_returnsFalse() {
        var testKit = KeyValueEntityTestKit.of("token-xyz", OAuthRefreshTokenEntity::new);
        testKit.method(OAuthRefreshTokenEntity::create)
                .invoke(createCmd(Instant.now().minusSeconds(1)));

        assertThat(testKit.getState().isValid()).isFalse();
    }
}
