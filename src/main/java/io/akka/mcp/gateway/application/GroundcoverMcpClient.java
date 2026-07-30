package io.akka.mcp.gateway.application;

import akka.javasdk.client.ComponentClient;
import io.akka.mcp.gateway.domain.McpConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpToolMetadataKeys;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GroundcoverMcpClient implements RemoteMcpClient {

    public static final String MCP_ID = "groundcover";
    public static final String MCP_NAME = "Groundcover";
    private static final Logger log = LoggerFactory.getLogger(GroundcoverMcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ComponentClient componentClient;
    private final String mcpUrl;
    private final String oktaAppId;

    public GroundcoverMcpClient(ComponentClient componentClient, String mcpUrl, String oktaAppId) {
        this.componentClient = componentClient;
        this.mcpUrl = mcpUrl;
        this.oktaAppId = oktaAppId;
    }

    @Override
    public String getMcpId() { return MCP_ID; }

    @Override
    public String getMcpName() { return MCP_NAME; }

    @Override
    public String getRequiredOktaAppId() { return oktaAppId; }

    @Override
    public HowToContent howTo(String dashboardUrl) {
        return new HowToContent(
                "Step-by-step instructions for connecting Groundcover observability to the MCP Gateway.",
                "Query logs, metrics, traces, APM, and monitors",
                """
                # How to Connect Groundcover

                ## Prerequisites
                - A Groundcover account with API access
                - Your Okta SSO login for the MCP Gateway

                ## Steps

                1. **Log in to the MCP Gateway dashboard**
                   Open %s and sign in with Okta.

                2. **Go to the Groundcover section**
                   Find the Groundcover card and click **Connect**.

                3. **Enter your API key**
                   Provide your Groundcover API key when prompted.

                4. **Return to the dashboard**
                   The Groundcover card now shows *Connected*.

                5. **Verify in your MCP client**
                   Run `tools/list` — Groundcover tools will appear in the list.

                ## Available capabilities
                - Query logs, metrics, and distributed traces
                - Inspect APM data and service performance
                - List and query monitors and alerts

                ## Troubleshooting
                - If tools are missing, verify that the Groundcover MCP URL is configured by your admin.
                """.formatted(dashboardUrl));
    }

    @Override
    public boolean isConnected(String userId) {
        if (mcpUrl.isBlank()) return false;
        try {
            fetchApiKey();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean canHandle(String toolName) {
        return toolName != null && toolName.startsWith("groundcover_");
    }

    @Override
    public List<ToolEntry> listTools(String userId) throws Exception {
        String apiKey = fetchApiKey();
        try (McpClient client = buildClient(apiKey)) {
            var specs = client.listTools();
            log.info("Fetched {} tools from Groundcover MCP", specs.size());
            List<ToolEntry> entries = new ArrayList<>();
            for (var spec : specs) {
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", spec.name());
                tool.put("description", spec.description());
                var inputSchema = McpSchemaUtils.schemaToMap(spec.parameters());
                tool.put("inputSchema", inputSchema);
                Map<String, Object> annotations = annotationsFromMetadata(spec.metadata());
                if (!annotations.isEmpty()) tool.put("annotations", annotations);

                Boolean readOnlyHint = extractReadOnlyHint(spec.metadata());
                boolean hasBodyParam = hasBodyParam(spec.parameters());
                var meta = new McpConfig.ToolMeta(spec.name(), spec.description(), inputSchema, readOnlyHint, hasBodyParam);
                entries.add(new ToolEntry(tool, meta));
            }
            return entries;
        }
    }

    @Override
    public ToolCallResult callTool(String userId, String toolName, Map<String, Object> arguments) throws Exception {
        String apiKey = fetchApiKey();
        return callToolRaw(apiKey, toolName, arguments);
    }

    private ToolCallResult callToolRaw(String apiKey, String toolName, Map<String, Object> arguments) throws Exception {
        var httpClient = HttpClient.newHttpClient();

        // Initialize session
        String initBody = MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "method", "initialize", "id", 1,
                "params", Map.of(
                        "protocolVersion", "2024-11-05",
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "mcp-gateway", "version", "1.0"))));
        var initResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(mcpUrl))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("MCP-Protocol-Version", "2024-11-05")
                        .header("Authorization", "Bearer " + apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(initBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        String sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElse(null);

        // Call tool
        String toolCallBody = MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "method", "tools/call", "id", 2,
                "params", Map.of("name", toolName, "arguments", arguments)));
        var reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2024-11-05")
                .header("Authorization", "Bearer " + apiKey);
        if (sessionId != null) reqBuilder.header("Mcp-Session-Id", sessionId);
        var toolResp = httpClient.send(
                reqBuilder.POST(HttpRequest.BodyPublishers.ofString(toolCallBody)).build(),
                HttpResponse.BodyHandlers.ofString());

        JsonNode json = MAPPER.readTree(extractFromSse(toolResp.body()));
        if (json.has("error")) {
            return new ToolCallResult(json.path("error").path("message").asText("Unknown error"), true);
        }

        JsonNode result = json.path("result");
        boolean isError = result.path("isError").asBoolean(false);
        StringBuilder text = new StringBuilder();
        for (JsonNode item : result.path("content")) {
            String type = item.path("type").asText();
            if ("text".equals(type)) {
                text.append(item.path("text").asText());
            } else if ("resource_link".equals(type)) {
                String uri = item.path("uri").asText();
                log.info("tools/call: following resource_link uri={}", uri);
                text.append(readResource(httpClient, apiKey, sessionId, uri));
            }
        }
        log.info("tools/call result isError={}", isError);
        return new ToolCallResult(text.toString(), isError);
    }

    private String readResource(HttpClient httpClient, String apiKey, String sessionId, String uri) throws Exception {
        String readBody = MAPPER.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "method", "resources/read", "id", 3,
                "params", Map.of("uri", uri)));
        var reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(mcpUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2024-11-05")
                .header("Authorization", "Bearer " + apiKey);
        if (sessionId != null) reqBuilder.header("Mcp-Session-Id", sessionId);
        var resp = httpClient.send(
                reqBuilder.POST(HttpRequest.BodyPublishers.ofString(readBody)).build(),
                HttpResponse.BodyHandlers.ofString());

        JsonNode json = MAPPER.readTree(extractFromSse(resp.body()));
        if (json.has("error")) {
            return "Error reading resource: " + json.path("error").path("message").asText();
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode content : json.path("result").path("contents")) {
            if (content.has("text")) text.append(content.path("text").asText());
        }
        return text.toString();
    }

    private static String extractFromSse(String body) {
        if (body == null) return "{}";
        if (body.contains("data:")) {
            return body.lines()
                    .filter(l -> l.startsWith("data:"))
                    .map(l -> l.substring(5).strip())
                    .filter(l -> !l.isEmpty())
                    .findFirst().orElse(body);
        }
        return body;
    }

    private String fetchApiKey() {
        return componentClient
                .forKeyValueEntity(GroundcoverConnectionEntity.ENTITY_ID)
                .method(GroundcoverConnectionEntity::getAccessToken)
                .invoke();
    }

    private static Boolean extractReadOnlyHint(Map<String, Object> metadata) {
        if (metadata == null) return null;
        Object val = metadata.get(McpToolMetadataKeys.READ_ONLY_HINT);
        if (val instanceof Boolean b) return b;
        return null;
    }

    private static boolean hasBodyParam(JsonSchemaElement schema) {
        if (!(schema instanceof JsonObjectSchema obj)) return false;
        return obj.properties() != null && obj.properties().containsKey("body");
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

    private McpClient buildClient(String apiKey) {
        var transport = new StreamableHttpMcpTransport.Builder()
                .url(mcpUrl)
                .customHeaders(Map.of("Authorization", "Bearer " + apiKey))
                .timeout(Duration.ofSeconds(30))
                .build();
        return new DefaultMcpClient.Builder()
                .transport(transport)
                .initializationTimeout(Duration.ofSeconds(15))
                .toolExecutionTimeout(Duration.ofSeconds(30))
                .build();
    }
}
