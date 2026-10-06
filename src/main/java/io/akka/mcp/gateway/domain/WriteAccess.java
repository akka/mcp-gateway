package io.akka.mcp.gateway.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides who may run write tools on which downstream MCP.
 *
 * Two independent conditions must both hold: the connector itself is write-enabled by the
 * operator, and the caller is in the writer group. An empty connector list means every MCP is
 * read-only, so an unconfigured deployment fails closed.
 */
public record WriteAccess(Set<String> enabledMcpIds, String writerGroup) {

    public WriteAccess {
        enabledMcpIds = Set.copyOf(enabledMcpIds);
    }

    /** @param enabledMcpIdsCsv comma-separated MCP ids, e.g. {@code "slack,google-workspace-gmail"}; blank enables none */
    public static WriteAccess parse(String enabledMcpIdsCsv, String writerGroup) {
        var ids = enabledMcpIdsCsv == null ? Set.<String>of()
                : Arrays.stream(enabledMcpIdsCsv.split(","))
                        .map(String::trim)
                        .filter(id -> !id.isEmpty())
                        .collect(Collectors.toSet());
        return new WriteAccess(ids, writerGroup);
    }

    public boolean connectorAllows(String mcpId) {
        return enabledMcpIds.contains(mcpId);
    }

    public boolean permits(String mcpId, UserSession session) {
        return connectorAllows(mcpId) && session.canWrite(writerGroup);
    }
}
