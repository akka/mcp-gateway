package io.akka.mcp.gateway.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpToolMetadataKeys;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import io.akka.mcp.gateway.domain.McpConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class McpSchemaUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Map<String, Object> schemaToMap(JsonSchemaElement element) {
        if (element == null) return Map.of();
        Map<String, Object> map = new LinkedHashMap<>();
        if (element instanceof JsonObjectSchema obj) {
            map.put("type", "object");
            if (obj.description() != null) map.put("description", obj.description());
            if (obj.properties() != null && !obj.properties().isEmpty()) {
                Map<String, Object> props = new LinkedHashMap<>();
                obj.properties().forEach((k, v) -> props.put(k, schemaToMap(v)));
                map.put("properties", props);
            }
            if (obj.required() != null && !obj.required().isEmpty()) map.put("required", obj.required());
            if (obj.additionalProperties() != null) map.put("additionalProperties", obj.additionalProperties());
            if (obj.definitions() != null && !obj.definitions().isEmpty()) {
                Map<String, Object> defs = new LinkedHashMap<>();
                obj.definitions().forEach((k, v) -> defs.put(k, schemaToMap(v)));
                map.put("$defs", defs);
            }
        } else if (element instanceof JsonStringSchema s) {
            map.put("type", "string");
            if (s.description() != null) map.put("description", s.description());
        } else if (element instanceof JsonIntegerSchema s) {
            map.put("type", "integer");
            if (s.description() != null) map.put("description", s.description());
        } else if (element instanceof JsonNumberSchema s) {
            map.put("type", "number");
            if (s.description() != null) map.put("description", s.description());
        } else if (element instanceof JsonBooleanSchema s) {
            map.put("type", "boolean");
            if (s.description() != null) map.put("description", s.description());
        } else if (element instanceof JsonArraySchema s) {
            map.put("type", "array");
            if (s.description() != null) map.put("description", s.description());
            if (s.items() != null) map.put("items", schemaToMap(s.items()));
        } else if (element instanceof JsonEnumSchema s) {
            map.put("type", "string");
            if (s.description() != null) map.put("description", s.description());
            map.put("enum", s.enumValues());
        } else if (element instanceof JsonAnyOfSchema s) {
            if (s.description() != null) map.put("description", s.description());
            map.put("anyOf", s.anyOf().stream().map(McpSchemaUtils::schemaToMap).toList());
        } else if (element instanceof JsonReferenceSchema s) {
            map.put("$ref", s.reference());
        } else if (element instanceof JsonRawSchema s) {
            try {
                return MAPPER.readValue(s.schema(), Map.class);
            } catch (Exception e) {
                map.put("schema", s.schema());
            }
        }
        return map;
    }

    /**
     * The gateway's view of an upstream tool listed under {@code exposedName}: its spec, annotations and read/write
     * classification. A tool that does not say it only reads is a write, whatever its parameters.
     */
    static RemoteMcpClient.ToolEntry toolEntry(String exposedName, ToolSpecification spec) {
        var inputSchema = schemaToMap(spec.parameters());
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", exposedName);
        tool.put("description", spec.description());
        tool.put("inputSchema", inputSchema);
        var annotations = annotationsFromMetadata(spec.metadata());
        if (!annotations.isEmpty()) tool.put("annotations", annotations);
        boolean readOnly = readOnlyHint(spec.metadata()).orElse(false);
        var meta = new McpConfig.ToolMeta(exposedName, spec.description(), inputSchema, readOnly, false);
        return new RemoteMcpClient.ToolEntry(tool, meta);
    }

    private static Optional<Boolean> readOnlyHint(Map<String, Object> metadata) {
        if (metadata == null) return Optional.empty();
        return metadata.get(McpToolMetadataKeys.READ_ONLY_HINT) instanceof Boolean hint ? Optional.of(hint) : Optional.empty();
    }

    private static Map<String, Object> annotationsFromMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return Map.of();
        Map<String, Object> annotations = new LinkedHashMap<>();
        for (var key : List.of(
                McpToolMetadataKeys.READ_ONLY_HINT,
                McpToolMetadataKeys.DESTRUCTIVE_HINT,
                McpToolMetadataKeys.IDEMPOTENT_HINT,
                McpToolMetadataKeys.OPEN_WORLD_HINT,
                McpToolMetadataKeys.TITLE)) {
            var value = metadata.get(key);
            if (value != null) annotations.put(key, value);
        }
        return annotations;
    }
}
