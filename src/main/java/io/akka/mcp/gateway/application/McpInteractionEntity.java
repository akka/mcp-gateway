package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.akka.mcp.gateway.domain.McpInteraction;
import io.akka.mcp.gateway.domain.McpInteractionEvent;

import java.time.Instant;
import java.util.Map;

@Component(id = "mcp-interaction")
public class McpInteractionEntity extends EventSourcedEntity<McpInteraction, McpInteractionEvent> {

    private final String interactionId;

    public McpInteractionEntity(EventSourcedEntityContext context) {
        this.interactionId = context.entityId();
    }

    @Override
    public McpInteraction emptyState() {
        return McpInteraction.empty();
    }

    public Effect<Done> record(RecordCommand cmd) {
        if (!currentState().isEmpty()) return effects().error("Interaction already recorded");
        var event = new McpInteractionEvent.InteractionRecorded(
                cmd.userId(), cmd.mcpId(), cmd.tool(), cmd.params(), Instant.now(), cmd.direction(), cmd.output());
        return effects().persist(event).thenReply(__ -> Done.getInstance());
    }

    public Effect<Done> updateEscalationStatus(String status) {
        if (currentState().isEmpty()) return effects().error("Interaction not found");
        return effects()
                .persist(new McpInteractionEvent.EscalationStatusUpdated(status))
                .thenReply(__ -> Done.getInstance());
    }

    public ReadOnlyEffect<McpInteraction> get() {
        return effects().reply(currentState());
    }

    @Override
    public McpInteraction applyEvent(McpInteractionEvent event) {
        return switch (event) {
            case McpInteractionEvent.InteractionRecorded e ->
                    new McpInteraction(interactionId, e.userId(), e.mcpId(), e.tool(), e.params(), null, e.timestamp(),
                            e.direction() != null ? e.direction() : "req", e.output());
            case McpInteractionEvent.EscalationStatusUpdated e ->
                    currentState().isEmpty() ? currentState() : currentState().withEscalationStatus(e.status());
        };
    }

    public record RecordCommand(String userId, String mcpId, String tool, Map<String, String> params, String direction, String output) {
        public RecordCommand(String userId, String mcpId, String tool, Map<String, String> params, String direction) {
            this(userId, mcpId, tool, params, direction, null);
        }
    }
}
