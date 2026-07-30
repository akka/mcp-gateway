package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.mcp.gateway.domain.McpInteractionEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class McpInteractionEntityTest {

    @Test
    public void record_persistsInteractionAndSetsFields() {
        var testKit = EventSourcedTestKit.of("interaction-1", McpInteractionEntity::new);
        var cmd = new McpInteractionEntity.RecordCommand("user-1", "salesforce", "query_accounts", Map.of("limit", "10"), "req");

        var result = testKit.method(McpInteractionEntity::record).invoke(cmd);

        assertThat(result.isReply()).isTrue();
        assertThat(result.getAllEvents()).hasSize(1);
        assertThat(result.getAllEvents().get(0)).isInstanceOf(McpInteractionEvent.InteractionRecorded.class);

        var state = testKit.getState();
        assertThat(state.interactionId()).isEqualTo("interaction-1");
        assertThat(state.userId()).isEqualTo("user-1");
        assertThat(state.mcpId()).isEqualTo("salesforce");
        assertThat(state.tool()).isEqualTo("query_accounts");
        assertThat(state.params()).containsEntry("limit", "10");
        assertThat(state.timestamp()).isNotNull();
        assertThat(state.escalationStatus()).isNull();
        assertThat(state.direction()).isEqualTo("req");
    }

    @Test
    public void record_twice_returnsError() {
        var testKit = EventSourcedTestKit.of("interaction-2", McpInteractionEntity::new);
        var cmd = new McpInteractionEntity.RecordCommand("user-1", "salesforce", "query_accounts", Map.of(), "req");

        testKit.method(McpInteractionEntity::record).invoke(cmd);
        var result = testKit.method(McpInteractionEntity::record).invoke(cmd);

        assertThat(result.isError()).isTrue();
    }

    @Test
    public void updateEscalationStatus_setsStatus() {
        var testKit = EventSourcedTestKit.of("interaction-3", McpInteractionEntity::new);
        testKit.method(McpInteractionEntity::record)
                .invoke(new McpInteractionEntity.RecordCommand("user-1", "zoho", "list_tickets", Map.of(), "req"));

        var result = testKit.method(McpInteractionEntity::updateEscalationStatus).invoke("REVIEW_REQUIRED");

        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().escalationStatus()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    public void updateEscalationStatus_whenEmpty_returnsError() {
        var testKit = EventSourcedTestKit.of("interaction-4", McpInteractionEntity::new);

        var result = testKit.method(McpInteractionEntity::updateEscalationStatus).invoke("REVIEW_REQUIRED");

        assertThat(result.isError()).isTrue();
    }

    @Test
    public void get_returnsCurrentState() {
        var testKit = EventSourcedTestKit.of("interaction-5", McpInteractionEntity::new);
        testKit.method(McpInteractionEntity::record)
                .invoke(new McpInteractionEntity.RecordCommand("user-2", "salesforce", "create_lead", Map.of("name", "Acme"), "req"));

        var result = testKit.method(McpInteractionEntity::get).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply().userId()).isEqualTo("user-2");
        assertThat(result.getReply().tool()).isEqualTo("create_lead");
    }
}
