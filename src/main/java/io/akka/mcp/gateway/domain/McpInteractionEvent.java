package io.akka.mcp.gateway.domain;

import akka.javasdk.annotations.TypeName;

import java.time.Instant;
import java.util.Map;

public sealed interface McpInteractionEvent {

    @TypeName("mcp-interaction-recorded")
    record InteractionRecorded(
            String userId,
            String mcpId,
            String tool,
            Map<String, String> params,
            Instant timestamp,
            String direction,  // "req", "resp", "connect-attempt", "connect-success", "connect-failed", "disconnect", "write-rejected"
            String output      // nullable; response text for "resp", error detail for failures
    ) implements McpInteractionEvent {}

    @TypeName("mcp-escalation-status-updated")
    record EscalationStatusUpdated(String status) implements McpInteractionEvent {}
}
