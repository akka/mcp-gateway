package io.akka.mcp.gateway.application;

import akka.javasdk.client.ComponentClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpToolMetadataKeys;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import io.akka.mcp.gateway.domain.McpConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class HubspotMcpClient implements RemoteMcpClient {

    public static final String MCP_ID = "hubspot";
    public static final String MCP_NAME = "HubSpot";
    private static final String TOOL_PREFIX = "HubSpot_";

    private static final Logger log = LoggerFactory.getLogger(HubspotMcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ComponentClient componentClient;
    private final String mcpUrl;
    private final String oktaAppId;

    public HubspotMcpClient(ComponentClient componentClient, String mcpUrl, String oktaAppId) {
        this.componentClient = componentClient;
        this.mcpUrl = mcpUrl;
        this.oktaAppId = oktaAppId;
    }

    @Override public String getMcpId() { return MCP_ID; }
    @Override public String getMcpName() { return MCP_NAME; }
    @Override public String getRequiredOktaAppId() { return oktaAppId; }

    @Override
    public HowToContent howTo(String dashboardUrl) {
        return new HowToContent(
                "Step-by-step instructions for connecting your HubSpot account to the MCP Gateway.",
                "Search contacts, companies, deals, and tickets in HubSpot",
                """
                # How to Connect HubSpot

                ## Prerequisites
                - A HubSpot account with API access
                - Your Okta SSO login for the MCP Gateway

                ## Steps

                1. **Log in to the MCP Gateway dashboard**
                   Open %s and sign in with Okta.

                2. **Go to the HubSpot section**
                   On the dashboard you will see a "HubSpot" card showing *Not connected*.

                3. **Click "Connect HubSpot"**
                   You will be redirected to the HubSpot OAuth consent screen.

                4. **Grant access**
                   Review the requested permissions and click *Connect app*.

                5. **Return to the dashboard**
                   You are redirected back automatically. The HubSpot card now shows *Connected*.

                6. **Verify in your MCP client**
                   Run `tools/list` — HubSpot tools (prefixed `HubSpot_`) will appear in the list.

                ## Available capabilities
                - Search and retrieve contacts, companies, deals, and tickets
                - Query CRM data and campaign analytics
                - Look up owners and properties

                ## Troubleshooting
                - Make sure your HubSpot account has the necessary API scopes.
                - If tools are missing, verify that the HubSpot MCP URL is configured by your admin.
                """.formatted(dashboardUrl));
    }

    @Override
    public boolean isConnected(String userId) {
        if (mcpUrl.isEmpty()) return false;
        try {
            fetchToken(userId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean canHandle(String toolName) {
        return toolName != null && toolName.startsWith(TOOL_PREFIX);
    }

    @Override
    public List<ToolEntry> listTools(String userId) throws Exception {
        String token = fetchToken(userId);
        try (McpClient client = buildClient(token)) {
            var specs = client.listTools();
            log.info("Fetched {} tools from HubSpot MCP", specs.size());
            List<ToolEntry> entries = new ArrayList<>();
            for (var spec : specs) {
                String prefixedName = TOOL_PREFIX + spec.name();
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", prefixedName);
                tool.put("description", spec.description());
                var inputSchema = McpSchemaUtils.schemaToMap(spec.parameters());
                tool.put("inputSchema", inputSchema);
                Map<String, Object> annotations = annotationsFromMetadata(spec.metadata());
                if (!annotations.isEmpty()) tool.put("annotations", annotations);
                Boolean readOnlyHint = extractReadOnlyHint(spec.metadata());
                boolean hasBodyParam = hasBodyParam(spec.parameters());
                entries.add(new ToolEntry(tool, new McpConfig.ToolMeta(prefixedName, spec.description(), inputSchema, readOnlyHint, hasBodyParam)));
            }
            return entries;
        }
    }

    @Override
    public ToolCallResult callTool(String userId, String toolName, Map<String, Object> arguments) throws Exception {
        String upstreamName = toolName.startsWith(TOOL_PREFIX) ? toolName.substring(TOOL_PREFIX.length()) : toolName;
        String token = fetchToken(userId);
        try (McpClient client = buildClient(token)) {
            String argsJson = MAPPER.writeValueAsString(arguments);
            var request = ToolExecutionRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .name(upstreamName)
                    .arguments(argsJson)
                    .build();
            var result = client.executeTool(request);
            log.info("tools/call result isError={}", result.isError());
            return new ToolCallResult(result.resultText(), result.isError());
        }
    }

    private String fetchToken(String userId) {
        return componentClient
                .forKeyValueEntity(userId)
                .method(HubspotConnectionEntity::getAccessToken)
                .invoke();
    }

    private McpClient buildClient(String token) {
        var headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + token);
        var transport = new StreamableHttpMcpTransport.Builder()
                .url(mcpUrl)
                .customHeaders(headers)
                .timeout(Duration.ofSeconds(30))
                .build();
        return new DefaultMcpClient.Builder()
                .transport(transport)
                .initializationTimeout(Duration.ofSeconds(15))
                .toolExecutionTimeout(Duration.ofSeconds(30))
                .build();
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
                McpToolMetadataKeys.READ_ONLY_HINT, McpToolMetadataKeys.DESTRUCTIVE_HINT,
                McpToolMetadataKeys.IDEMPOTENT_HINT, McpToolMetadataKeys.OPEN_WORLD_HINT,
                McpToolMetadataKeys.TITLE)) {
            var value = metadata.get(key);
            if (value != null) annotations.put(key, value);
        }
        return annotations;
    }
}
