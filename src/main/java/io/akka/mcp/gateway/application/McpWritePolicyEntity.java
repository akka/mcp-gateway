package io.akka.mcp.gateway.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;

import java.time.Instant;
import java.util.List;

/**
 * The admin-chosen set of MCPs whose write tools may run. Until an admin first saves a
 * selection the state is unconfigured and the deployment config ({@code mcp.write-enabled})
 * applies; after that the saved selection is authoritative, including an empty one.
 */
@Component(id = "mcp-write-policy")
public class McpWritePolicyEntity extends KeyValueEntity<McpWritePolicyEntity.State> {

    public static final String ENTITY_ID = "default";

    public record State(boolean configured, List<String> enabledMcpIds, String updatedBy, Instant updatedAt) {
        public static State unconfigured() {
            return new State(false, List.of(), null, null);
        }
    }

    public record SetCommand(List<String> enabledMcpIds, String updatedBy) {}

    @Override
    public State emptyState() {
        return State.unconfigured();
    }

    public Effect<State> set(SetCommand command) {
        var ids = command.enabledMcpIds().stream()
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .distinct()
                .sorted()
                .toList();
        var next = new State(true, ids, command.updatedBy(), Instant.now());
        return effects().updateState(next).thenReply(next);
    }

    public ReadOnlyEffect<State> get() {
        return effects().reply(currentState());
    }
}
