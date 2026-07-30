package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class OidcPendingLoginEntityTest {

    @Test
    public void create_storesPendingLogin() {
        var testKit = KeyValueEntityTestKit.of("state-id", OidcPendingLoginEntity::new);
        var cmd = new OidcPendingLoginEntity.CreateCommand("alice@lightbend.com", Instant.now().plusSeconds(600), "verifier-abc");
        var result = testKit.method(OidcPendingLoginEntity::create).invoke(cmd);
        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().loginHint()).isEqualTo("alice@lightbend.com");
        assertThat(testKit.getState().isEmpty()).isFalse();
    }

    @Test
    public void get_whenEmpty_returnsEmptyState() {
        var testKit = KeyValueEntityTestKit.of("state-id", OidcPendingLoginEntity::new);
        var result = testKit.method(OidcPendingLoginEntity::get).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply().isEmpty()).isTrue();
    }

    @Test
    public void get_whenCreated_returnsPending() {
        var testKit = KeyValueEntityTestKit.of("state-id", OidcPendingLoginEntity::new);
        var cmd = new OidcPendingLoginEntity.CreateCommand("bob@lightbend.com", Instant.now().plusSeconds(600), "verifier-bob");
        testKit.method(OidcPendingLoginEntity::create).invoke(cmd);
        var result = testKit.method(OidcPendingLoginEntity::get).invoke();
        assertThat(result.getReply().loginHint()).isEqualTo("bob@lightbend.com");
        assertThat(result.getReply().isEmpty()).isFalse();
        assertThat(result.getReply().isExpired()).isFalse();
    }

    @Test
    public void get_whenExpired_isExpired() {
        var testKit = KeyValueEntityTestKit.of("state-id", OidcPendingLoginEntity::new);
        var cmd = new OidcPendingLoginEntity.CreateCommand("carol@lightbend.com", Instant.now().minusSeconds(1), "verifier-carol");
        testKit.method(OidcPendingLoginEntity::create).invoke(cmd);
        var result = testKit.method(OidcPendingLoginEntity::get).invoke();
        assertThat(result.getReply().isExpired()).isTrue();
    }

    @Test
    public void delete_clearsPendingLogin() {
        var testKit = KeyValueEntityTestKit.of("state-id", OidcPendingLoginEntity::new);
        var cmd = new OidcPendingLoginEntity.CreateCommand("dave@lightbend.com", Instant.now().plusSeconds(600), "verifier-dave");
        testKit.method(OidcPendingLoginEntity::create).invoke(cmd);
        var result = testKit.method(OidcPendingLoginEntity::delete).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().isEmpty()).isTrue();
    }
}
