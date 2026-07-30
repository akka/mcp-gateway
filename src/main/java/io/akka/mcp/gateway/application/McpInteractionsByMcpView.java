package io.akka.mcp.gateway.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.mcp.gateway.domain.McpInteractionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component(id = "mcp-interactions-by-mcp")
public class McpInteractionsByMcpView extends View {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record McpInteractionEntry(
            String interactionId,
            String userId,
            String mcpId,
            String tool,
            String params,
            String escalationStatus,
            Instant timestamp,
            String direction,
            Optional<String> output
    ) {
        public McpInteractionEntry withEscalationStatus(String status) {
            return new McpInteractionEntry(interactionId, userId, mcpId, tool, params, status, timestamp, direction, output);
        }
    }

    public record McpInteractionEntries(List<McpInteractionEntry> interactions, long totalCount) {}

    public record McpPageRequest(String mcpId, int offset, int pageSize) {}

    @Consume.FromEventSourcedEntity(McpInteractionEntity.class)
    public static class McpInteractionsByMcpUpdater extends TableUpdater<McpInteractionEntry> {

        public Effect<McpInteractionEntry> onEvent(McpInteractionEvent event) {
            var interactionId = updateContext().eventSubject().orElse("");
            return switch (event) {
                case McpInteractionEvent.InteractionRecorded e ->
                        effects().updateRow(new McpInteractionEntry(
                                interactionId, e.userId(), e.mcpId(), e.tool(), toJson(e.params()), "", e.timestamp(),
                                e.direction() != null ? e.direction() : "req", Optional.ofNullable(e.output())));
                case McpInteractionEvent.EscalationStatusUpdated e ->
                        effects().updateRow(rowState().withEscalationStatus(e.status()));
            };
        }
    }

    @Query("SELECT * AS interactions, total_count() AS totalCount FROM mcp_interactions_by_mcp WHERE mcpId = :mcpId ORDER BY timestamp DESC OFFSET :offset LIMIT :pageSize")
    public QueryEffect<McpInteractionEntries> getByMcp(McpPageRequest request) {
        return queryResult();
    }

    private static String toJson(Map<String, String> params) {
        try {
            return MAPPER.writeValueAsString(params);
        } catch (Exception e) {
            return "{}";
        }
    }
}
