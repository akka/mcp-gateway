package io.akka.mcp.gateway.application;

import akka.javasdk.client.ComponentClient;
import io.akka.mcp.gateway.domain.McpConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpToolMetadataKeys;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ReoMcpClient implements RemoteMcpClient {

    public static final String MCP_ID = "reo";
    public static final String MCP_NAME = "Reo";
    private static final Logger log = LoggerFactory.getLogger(ReoMcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ComponentClient componentClient;
    private final String reoMcpUrl;
    private final String oktaAppId;

    public ReoMcpClient(ComponentClient componentClient, String reoMcpUrl, String oktaAppId) {
        this.componentClient = componentClient;
        this.reoMcpUrl = reoMcpUrl;
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
                "Step-by-step instructions for connecting your Reo account to the MCP Gateway.",
                "Query developer activity, accounts, segments, lists",
                """
                # How to Connect Reo

                ## Prerequisites
                - A Reo account with API access
                - Your Okta SSO login for the MCP Gateway

                ## Steps

                1. **Log in to the MCP Gateway dashboard**
                   Open %s and sign in with Okta.

                2. **Go to the Reo section**
                   On the dashboard you will see a "Reo" card showing *Not connected*.

                3. **Click "Connect Reo"**
                   You will be redirected to the Reo OAuth consent screen.

                4. **Authorise the application**
                   Log in with your Reo credentials and grant access.

                5. **Return to the dashboard**
                   You are redirected back automatically. The Reo card now shows *Connected*.

                6. **Verify in your MCP client**
                   Run `tools/list` — Reo tools (prefixed `reo_`) will appear in the list.

                ## Available capabilities (read-only)
                - Query developer activity and profiles
                - List accounts, segments, and audiences
                - Search and filter leads and prospects

                ## Troubleshooting
                - Reo API tokens are scoped to your user — ensure your Reo role includes API access.
                - If the connection button is absent, the Reo MCP URL may not be configured; contact your admin.
                """.formatted(dashboardUrl));
    }

    @Override
    public boolean isConnected(String userId) {
        if (reoMcpUrl.isEmpty()) return false;
        try {
            fetchToken(userId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean canHandle(String toolName) {
        return toolName != null && toolName.startsWith("reo_");
    }

    @Override
    public List<ToolEntry> listTools(String userId) throws Exception {
        String token = fetchToken(userId);
        try (McpClient client = buildClient(token)) {
            var specs = client.listTools();
            log.info("Fetched {} tools from Reo MCP", specs.size());
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
        String token = fetchToken(userId);
        try (McpClient client = buildClient(token)) {
            String argsJson = MAPPER.writeValueAsString(arguments);
            var request = ToolExecutionRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .name(toolName)
                    .arguments(argsJson)
                    .build();
            var result = client.executeTool(request);
            log.info("tools/call result isError={}", result.isError());
            return new ToolCallResult(result.resultText(), result.isError());
        }
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

    private String fetchToken(String userId) {
        return componentClient
                .forKeyValueEntity(userId)
                .method(ReoConnectionEntity::getAccessToken)
                .invoke();
    }

    private McpClient buildClient(String token) {
        var transport = new StreamableHttpMcpTransport.Builder()
                .url(reoMcpUrl)
                .customHeaders(Map.of("Authorization", "Bearer " + token))
                .timeout(Duration.ofSeconds(30))
                .build();
        return new DefaultMcpClient.Builder()
                .transport(transport)
                .initializationTimeout(Duration.ofSeconds(15))
                .toolExecutionTimeout(Duration.ofSeconds(30))
                .build();
    }
}
