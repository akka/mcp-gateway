package io.akka.mcp.gateway.domain;

import java.time.Instant;
import java.util.List;

/**
 * The set of MCPs an admin has enabled write tools on. Nothing is enabled until an admin selects
 * some. {@code version} counts the saved changes, so a save can be rejected when it was made from a
 * stale view.
 */
public record WritePolicy(List<String> enabledMcpIds, long version, String updatedBy, Instant updatedAt) {

    public static WritePolicy nothingEnabled() {
        return new WritePolicy(List.of(), 0, null, null);
    }

    public boolean isBasedOn(long version) {
        return this.version == version;
    }

    public boolean enables(List<String> mcpIds) {
        return enabledMcpIds.equals(normalized(mcpIds));
    }

    public WritePolicy select(List<String> mcpIds, String updatedBy, Instant updatedAt) {
        return new WritePolicy(normalized(mcpIds), version + 1, updatedBy, updatedAt);
    }

    public List<String> addedSince(List<String> previousMcpIds) {
        return enabledMcpIds.stream().filter(id -> !previousMcpIds.contains(id)).toList();
    }

    public List<String> removedSince(List<String> previousMcpIds) {
        return previousMcpIds.stream().filter(id -> !enabledMcpIds.contains(id)).toList();
    }

    private static List<String> normalized(List<String> mcpIds) {
        return mcpIds.stream()
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }
}
