package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class UserSessionEntityTest {

    @Test
    public void create_storesSession() {
        var testKit = KeyValueEntityTestKit.of("session-id", UserSessionEntity::new);
        var cmd = new UserSessionEntity.CreateCommand("alice@lightbend.com", "Alice", Instant.now().plusSeconds(3600), List.of(), null, List.of());
        var result = testKit.method(UserSessionEntity::create).invoke(cmd);
        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().email()).isEqualTo("alice@lightbend.com");
        assertThat(testKit.getState().displayName()).isEqualTo("Alice");
        assertThat(testKit.getState().isEmpty()).isFalse();
    }

    @Test
    public void getSession_whenEmpty_returnsEmptyState() {
        var testKit = KeyValueEntityTestKit.of("session-id", UserSessionEntity::new);
        var result = testKit.method(UserSessionEntity::getSession).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply().isEmpty()).isTrue();
    }

    @Test
    public void getSession_whenCreated_returnsSession() {
        var testKit = KeyValueEntityTestKit.of("session-id", UserSessionEntity::new);
        var cmd = new UserSessionEntity.CreateCommand("bob@lightbend.com", "Bob", Instant.now().plusSeconds(3600), List.of(), null, List.of());
        testKit.method(UserSessionEntity::create).invoke(cmd);
        var result = testKit.method(UserSessionEntity::getSession).invoke();
        assertThat(result.getReply().email()).isEqualTo("bob@lightbend.com");
        assertThat(result.getReply().isEmpty()).isFalse();
        assertThat(result.getReply().isExpired()).isFalse();
    }

    @Test
    public void getSession_whenExpired_isExpired() {
        var testKit = KeyValueEntityTestKit.of("session-id", UserSessionEntity::new);
        var cmd = new UserSessionEntity.CreateCommand("carol@lightbend.com", "Carol", Instant.now().minusSeconds(1), List.of(), null, List.of());
        testKit.method(UserSessionEntity::create).invoke(cmd);
        var result = testKit.method(UserSessionEntity::getSession).invoke();
        assertThat(result.getReply().isExpired()).isTrue();
    }

    @Test
    public void invalidate_clearsSession() {
        var testKit = KeyValueEntityTestKit.of("session-id", UserSessionEntity::new);
        var cmd = new UserSessionEntity.CreateCommand("dave@lightbend.com", "Dave", Instant.now().plusSeconds(3600), List.of(), null, List.of());
        testKit.method(UserSessionEntity::create).invoke(cmd);
        var result = testKit.method(UserSessionEntity::invalidate).invoke();
        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().isEmpty()).isTrue();
    }
}
