package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class McpWritePolicyEntityTest {

    @Test
    public void get_beforeAnyoneSaved_isUnconfigured() {
        var testKit = KeyValueEntityTestKit.of(McpWritePolicyEntity.ENTITY_ID, McpWritePolicyEntity::new);

        var state = testKit.method(McpWritePolicyEntity::get).invoke().getReply();

        assertThat(state.configured()).isFalse();
        assertThat(state.enabledMcpIds()).isEmpty();
    }

    @Test
    public void set_storesTheSelectionTidiedAndRecordsWhoSavedIt() {
        var testKit = KeyValueEntityTestKit.of(McpWritePolicyEntity.ENTITY_ID, McpWritePolicyEntity::new);

        var state = testKit.method(McpWritePolicyEntity::set)
                .invoke(new McpWritePolicyEntity.SetCommand(List.of("slack", " google-workspace-gmail ", "slack", ""), "admin@example.com"))
                .getReply();

        assertThat(state.configured()).isTrue();
        assertThat(state.enabledMcpIds()).containsExactly("google-workspace-gmail", "slack");
        assertThat(state.updatedBy()).isEqualTo("admin@example.com");
        assertThat(state.updatedAt()).isNotNull();
        assertThat(testKit.getState()).isEqualTo(state);
    }

    @Test
    public void set_withNothingSelected_isStillConfigured() {
        var testKit = KeyValueEntityTestKit.of(McpWritePolicyEntity.ENTITY_ID, McpWritePolicyEntity::new);

        var state = testKit.method(McpWritePolicyEntity::set)
                .invoke(new McpWritePolicyEntity.SetCommand(List.of(), "admin@example.com"))
                .getReply();

        assertThat(state.configured()).isTrue();
        assertThat(state.enabledMcpIds()).isEmpty();
    }

    @Test
    public void set_replacesThePreviousSelection() {
        var testKit = KeyValueEntityTestKit.of(McpWritePolicyEntity.ENTITY_ID, McpWritePolicyEntity::new);
        testKit.method(McpWritePolicyEntity::set).invoke(new McpWritePolicyEntity.SetCommand(List.of("slack"), "a@example.com"));

        testKit.method(McpWritePolicyEntity::set).invoke(new McpWritePolicyEntity.SetCommand(List.of("hubspot"), "b@example.com"));

        assertThat(testKit.getState().enabledMcpIds()).containsExactly("hubspot");
        assertThat(testKit.getState().updatedBy()).isEqualTo("b@example.com");
    }
}
