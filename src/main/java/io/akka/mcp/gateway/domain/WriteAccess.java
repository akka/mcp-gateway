package io.akka.mcp.gateway.domain;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
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

    /**
     * The selection an admin saved wins; with none saved the deployment config applies. If the saved
     * selection cannot be read the answer is no writes anywhere rather than the config value, since
     * config may be broader than what an admin deliberately narrowed.
     *
     * @param stored the admin's saved selection, empty if an admin has never saved one
     */
    public static WriteAccess resolve(Supplier<Optional<Set<String>>> stored, WriteAccess configured,
                                      Consumer<RuntimeException> onStoreFailure) {
        try {
            return stored.get()
                    .map(ids -> new WriteAccess(ids, configured.writerGroup()))
                    .orElse(configured);
        } catch (RuntimeException e) {
            onStoreFailure.accept(e);
            return new WriteAccess(Set.of(), configured.writerGroup());
        }
    }

    public boolean connectorAllows(String mcpId) {
        return enabledMcpIds.contains(mcpId);
    }

    public boolean permits(String mcpId, UserSession session) {
        return connectorAllows(mcpId) && session.canWrite(writerGroup);
    }
}
