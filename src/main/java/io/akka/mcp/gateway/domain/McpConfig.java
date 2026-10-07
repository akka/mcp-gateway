package io.akka.mcp.gateway.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record McpConfig(String mcpId, String name, List<ToolMeta> tools) {

    public McpConfig {
        if (tools == null) tools = List.of();
    }

    public McpConfig(String mcpId, String name) {
        this(mcpId, name, List.of());
    }

    /**
     * Per-tool read/write classification derived from MCP annotations and input schema shape.
     *
     * Detection priority:
     *   1. readOnlyHint from the upstream MCP server's tool annotations (authoritative)
     *   2. hasBodyParam — presence of a "body" key in the tool's input schema (reliable for REST-mapped tools)
     *   3. Callers should default to write when meta is absent (safe fallback)
     */
    public record ToolMeta(String name, String description, Map<String, Object> inputSchema,
                           Boolean readOnlyHint, boolean hasBodyParam) {
        public boolean isWrite() {
            if (readOnlyHint != null) return !readOnlyHint;
            return hasBodyParam;
        }

        public Map<String, Object> toToolSpec() {
            var spec = new java.util.LinkedHashMap<String, Object>();
            spec.put("name", name);
            if (description != null) spec.put("description", description);
            // MCP spec requires inputSchema on every tool; fall back to empty object schema
            spec.put("inputSchema", inputSchema != null ? inputSchema
                    : Map.of("type", "object", "properties", Map.of()));
            return spec;
        }
    }

    public Optional<ToolMeta> findTool(String toolName) {
        return tools.stream().filter(t -> t.name().equals(toolName)).findFirst();
    }
}
