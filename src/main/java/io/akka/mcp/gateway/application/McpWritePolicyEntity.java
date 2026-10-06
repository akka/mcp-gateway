package io.akka.mcp.gateway.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.mcp.gateway.domain.WritePolicy;

import java.time.Instant;
import java.util.List;

@Component(id = "mcp-write-policy")
public class McpWritePolicyEntity extends KeyValueEntity<WritePolicy> {

    public static final String ENTITY_ID = "default";

    /** @param basedOnVersion the version the caller was looking at; a different current version makes this a stale save */
    public record SelectCommand(List<String> enabledMcpIds, long basedOnVersion, String updatedBy) {}

    public enum Outcome { SAVED, UNCHANGED, STALE }

    /** @param previousEnabledMcpIds what was enabled immediately before this command, for auditing the difference */
    public record SelectResult(Outcome outcome, WritePolicy policy, List<String> previousEnabledMcpIds) {}

    @Override
    public WritePolicy emptyState() {
        return WritePolicy.nothingEnabled();
    }

    public Effect<SelectResult> select(SelectCommand command) {
        var current = currentState();
        var previous = current.enabledMcpIds();
        if (!current.isBasedOn(command.basedOnVersion())) {
            return effects().reply(new SelectResult(Outcome.STALE, current, previous));
        }
        if (current.enables(command.enabledMcpIds())) {
            return effects().reply(new SelectResult(Outcome.UNCHANGED, current, previous));
        }
        var next = current.select(command.enabledMcpIds(), command.updatedBy(), Instant.now());
        return effects().updateState(next).thenReply(new SelectResult(Outcome.SAVED, next, previous));
    }

    public ReadOnlyEffect<WritePolicy> get() {
        return effects().reply(currentState());
    }
}
