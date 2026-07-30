package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.mcp.gateway.domain.McpConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component(id = "mcp-registry")
public class McpRegistryEntity extends KeyValueEntity<McpRegistryEntity.State> {

    public static final String ENTITY_ID = "default";

    public record State(List<McpConfig> configs) {
        public static State empty() { return new State(List.of()); }

        public State with(McpConfig config) {
            var updated = new ArrayList<>(configs);
            updated.removeIf(c -> c.mcpId().equals(config.mcpId()));
            updated.add(config);
            return new State(List.copyOf(updated));
        }

        public Optional<McpConfig.ToolMeta> findTool(String toolName) {
            return configs.stream()
                    .flatMap(c -> c.findTool(toolName).stream())
                    .findFirst();
        }

        public Optional<String> findMcpIdForTool(String toolName) {
            return configs.stream()
                    .filter(c -> c.findTool(toolName).isPresent())
                    .map(McpConfig::mcpId)
                    .findFirst();
        }

        public Optional<McpConfig> findByMcpId(String mcpId) {
            return configs.stream().filter(c -> c.mcpId().equals(mcpId)).findFirst();
        }
    }

    @Override
    public State emptyState() {
        return State.empty();
    }

    public Effect<Done> register(McpConfig config) {
        return effects().updateState(currentState().with(config)).thenReply(Done.getInstance());
    }

    public ReadOnlyEffect<State> list() {
        return effects().reply(currentState());
    }

    public ReadOnlyEffect<Optional<McpConfig.ToolMeta>> findTool(String toolName) {
        return effects().reply(currentState().findTool(toolName));
    }

    public ReadOnlyEffect<Optional<String>> findMcpIdForTool(String toolName) {
        return effects().reply(currentState().findMcpIdForTool(toolName));
    }

    public ReadOnlyEffect<Optional<McpConfig>> findByMcpId(String mcpId) {
        return effects().reply(currentState().findByMcpId(mcpId));
    }
}
