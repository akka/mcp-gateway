package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.akka.mcp.gateway.application.McpWritePolicyEntity.Outcome.SAVED;
import static io.akka.mcp.gateway.application.McpWritePolicyEntity.Outcome.STALE;
import static io.akka.mcp.gateway.application.McpWritePolicyEntity.Outcome.UNCHANGED;
import static org.assertj.core.api.Assertions.assertThat;

public class McpWritePolicyEntityTest {

    private static KeyValueEntityTestKit<io.akka.mcp.gateway.domain.WritePolicy, McpWritePolicyEntity> newTestKit() {
        return KeyValueEntityTestKit.of(McpWritePolicyEntity.ENTITY_ID, McpWritePolicyEntity::new);
    }

    private static McpWritePolicyEntity.SelectCommand select(long basedOn, String by, String... ids) {
        return new McpWritePolicyEntity.SelectCommand(List.of(ids), basedOn, by);
    }

    @Test
    public void get_beforeAnyoneSaved_hasNothingEnabled() {
        var policy = newTestKit().method(McpWritePolicyEntity::get).invoke().getReply();

        assertThat(policy.enabledMcpIds()).isEmpty();
        assertThat(policy.updatedBy()).isNull();
    }

    @Test
    public void select_savesAndReportsWhatWasEnabledBefore() {
        var testKit = newTestKit();

        var result = testKit.method(McpWritePolicyEntity::select).invoke(select(0, "admin@example.com", "slack")).getReply();

        assertThat(result.outcome()).isEqualTo(SAVED);
        assertThat(result.policy().enabledMcpIds()).containsExactly("slack");
        assertThat(result.policy().updatedBy()).isEqualTo("admin@example.com");
        assertThat(result.previousEnabledMcpIds()).isEmpty();
        assertThat(testKit.getState()).isEqualTo(result.policy());
    }

    @Test
    public void select_replacesThePreviousSelection() {
        var testKit = newTestKit();
        testKit.method(McpWritePolicyEntity::select).invoke(select(0, "a@example.com", "slack"));

        var result = testKit.method(McpWritePolicyEntity::select).invoke(select(1, "b@example.com", "hubspot")).getReply();

        assertThat(result.outcome()).isEqualTo(SAVED);
        assertThat(result.previousEnabledMcpIds()).containsExactly("slack");
        assertThat(testKit.getState().enabledMcpIds()).containsExactly("hubspot");
        assertThat(testKit.getState().updatedBy()).isEqualTo("b@example.com");
    }

    @Test
    public void select_withNothingSelected_clearsEarlierSelections() {
        var testKit = newTestKit();
        testKit.method(McpWritePolicyEntity::select).invoke(select(0, "a@example.com", "slack"));

        var result = testKit.method(McpWritePolicyEntity::select).invoke(select(1, "b@example.com")).getReply();

        assertThat(result.outcome()).isEqualTo(SAVED);
        assertThat(testKit.getState().enabledMcpIds()).isEmpty();
    }

    @Test
    public void select_ofTheSameIds_changesNothing() {
        var testKit = newTestKit();
        testKit.method(McpWritePolicyEntity::select).invoke(select(0, "a@example.com", "slack", "hubspot"));
        var before = testKit.getState();

        var result = testKit.method(McpWritePolicyEntity::select).invoke(select(1, "b@example.com", "hubspot", "slack")).getReply();

        assertThat(result.outcome()).isEqualTo(UNCHANGED);
        assertThat(testKit.getState()).isEqualTo(before);
    }

    @Test
    public void select_basedOnAnOlderVersion_isRejectedAndLeavesTheNewerChangeInPlace() {
        var testKit = newTestKit();
        testKit.method(McpWritePolicyEntity::select).invoke(select(0, "a@example.com", "slack"));

        var result = testKit.method(McpWritePolicyEntity::select).invoke(select(0, "b@example.com", "hubspot")).getReply();

        assertThat(result.outcome()).isEqualTo(STALE);
        assertThat(result.policy().enabledMcpIds()).containsExactly("slack");
        assertThat(testKit.getState().enabledMcpIds()).containsExactly("slack");
        assertThat(testKit.getState().updatedBy()).isEqualTo("a@example.com");
    }
}
