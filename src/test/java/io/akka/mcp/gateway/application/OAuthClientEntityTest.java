package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class OAuthClientEntityTest {

    @Test
    public void register_storesClient() {
        var testKit = KeyValueEntityTestKit.of("client-1", OAuthClientEntity::new);
        var cmd = new OAuthClientEntity.RegisterCommand("client-1", "My MCP Client", "http://localhost/callback");

        var result = testKit.method(OAuthClientEntity::register).invoke(cmd);

        assertThat(result.isReply()).isTrue();
        var state = testKit.getState();
        assertThat(state.clientId()).isEqualTo("client-1");
        assertThat(state.clientName()).isEqualTo("My MCP Client");
        assertThat(state.redirectUri()).isEqualTo("http://localhost/callback");
        assertThat(state.registeredAt()).isNotNull();
        assertThat(state.isEmpty()).isFalse();
    }

    @Test
    public void get_whenEmpty_returnsEmptyState() {
        var testKit = KeyValueEntityTestKit.of("client-1", OAuthClientEntity::new);

        var result = testKit.method(OAuthClientEntity::get).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply().isEmpty()).isTrue();
    }

    @Test
    public void get_afterRegister_returnsStoredClient() {
        var testKit = KeyValueEntityTestKit.of("client-1", OAuthClientEntity::new);
        testKit.method(OAuthClientEntity::register)
                .invoke(new OAuthClientEntity.RegisterCommand("client-1", "My MCP Client", "http://localhost/callback"));

        var result = testKit.method(OAuthClientEntity::get).invoke();

        assertThat(result.getReply().clientId()).isEqualTo("client-1");
        assertThat(result.getReply().clientName()).isEqualTo("My MCP Client");
    }

    @Test
    public void register_overwritesPreviousRegistration() {
        var testKit = KeyValueEntityTestKit.of("client-1", OAuthClientEntity::new);
        testKit.method(OAuthClientEntity::register)
                .invoke(new OAuthClientEntity.RegisterCommand("client-1", "Old Name", "http://old/callback"));
        testKit.method(OAuthClientEntity::register)
                .invoke(new OAuthClientEntity.RegisterCommand("client-1", "New Name", "http://new/callback"));

        assertThat(testKit.getState().clientName()).isEqualTo("New Name");
        assertThat(testKit.getState().redirectUri()).isEqualTo("http://new/callback");
    }
}
