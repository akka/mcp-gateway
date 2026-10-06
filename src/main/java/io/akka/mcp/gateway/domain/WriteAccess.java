package io.akka.mcp.gateway.domain;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Decides who may run write tools on which downstream MCP.
 *
 * Two independent conditions must both hold: an admin has enabled writes on the connector, and the
 * caller is in the writer group. With nothing enabled every MCP is read-only.
 */
public record WriteAccess(Set<String> enabledMcpIds, String writerGroup) {

    public WriteAccess {
        enabledMcpIds = Set.copyOf(enabledMcpIds);
    }

    /** If the saved selection cannot be read the answer is no writes anywhere, and the failure is reported. */
    public static WriteAccess resolve(Supplier<Set<String>> saved, String writerGroup,
                                      Consumer<RuntimeException> onReadFailure) {
        try {
            return new WriteAccess(saved.get(), writerGroup);
        } catch (RuntimeException e) {
            onReadFailure.accept(e);
            return new WriteAccess(Set.of(), writerGroup);
        }
    }

    public boolean connectorAllows(String mcpId) {
        return enabledMcpIds.contains(mcpId);
    }

    public boolean permits(String mcpId, UserSession session) {
        return connectorAllows(mcpId) && session.canWrite(writerGroup);
    }
}
