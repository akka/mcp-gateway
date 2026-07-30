package io.akka.mcp.gateway.domain;

import java.time.Instant;
import java.util.Map;

public record McpInteraction(
        String interactionId,
        String userId,
        String mcpId,
        String tool,
        Map<String, String> params,
        String escalationStatus,
        Instant timestamp,
        String direction,
        String output
) {
    public static McpInteraction empty() {
        return new McpInteraction(null, null, null, null, Map.of(), null, null, null, null);
    }

    public boolean isEmpty() {
        return interactionId == null;
    }

    public McpInteraction withEscalationStatus(String status) {
        return new McpInteraction(interactionId, userId, mcpId, tool, params, status, timestamp, direction, output);
    }
}
