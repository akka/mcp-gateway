package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.mcp.gateway.domain.McpConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

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

        public State withToolsAdded(McpConfig config) {
            var known = findByMcpId(config.mcpId());
            var tools = new ArrayList<>(known.map(McpConfig::tools).orElse(List.of()));
            for (var offered : config.tools()) {
                var at = IntStream.range(0, tools.size()).filter(i -> tools.get(i).name().equals(offered.name())).findFirst();
                if (at.isPresent()) {
                    tools.set(at.getAsInt(), keepingWrite(tools.get(at.getAsInt()), offered));
                } else {
                    tools.add(offered);
                }
            }
            var merged = new McpConfig(config.mcpId(), config.name(), List.copyOf(tools));
            return known.isPresent() ? replacingInPlace(merged) : with(merged);
        }

        private State replacingInPlace(McpConfig config) {
            return new State(configs.stream().map(c -> c.mcpId().equals(config.mcpId()) ? config : c).toList());
        }

        /** A tool once seen as a write stays one, so a listing that leaves out its hints cannot open the write gate. */
        private static McpConfig.ToolMeta keepingWrite(McpConfig.ToolMeta known, McpConfig.ToolMeta offered) {
            if (!known.isWrite() || offered.isWrite()) return offered;
            return new McpConfig.ToolMeta(offered.name(), offered.description(), offered.inputSchema(), false, offered.hasBodyParam());
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

    public Effect<Done> registerAdding(McpConfig config) {
        var next = currentState().withToolsAdded(config);
        if (next.equals(currentState())) return effects().reply(Done.getInstance());
        return effects().updateState(next).thenReply(Done.getInstance());
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
