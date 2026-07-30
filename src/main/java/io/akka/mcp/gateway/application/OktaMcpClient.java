package io.akka.mcp.gateway.application;

import akka.javasdk.http.HttpClientProvider;
import io.akka.mcp.gateway.domain.McpConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpToolMetadataKeys;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Talks to the internal okta-mcp-server, which authenticates to the Okta Management API with a
 * single org-wide token (not per-user OAuth), so no bearer token is sent here. Access is gated by
 * the endpoint's {@code @Acl(service = "mcp-gateway")} service-principal ACL: the gateway must
 * dial the server by its internal service name (i.e. {@code OKTA_ADMIN_MCP_URL=okta-mcp-server},
 * not a public route URL) so Akka attaches the {@code mcp-gateway} principal to the call.
 * Reaching it via a public {@code *.akka.services} route strips that principal and the ACL 403s.
 */
public class OktaMcpClient implements RemoteMcpClient {

    public static final String MCP_ID = "okta-admin";
    public static final String MCP_NAME = "Okta";
    private static final String TOOL_PREFIX = "okta_";

    private final HttpClientProvider httpClientProvider;

    private static final Logger log = LoggerFactory.getLogger(OktaMcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String mcpUrl;
    private final String oktaAppId;

    public OktaMcpClient(String mcpUrl, HttpClientProvider httpClientProvider, String oktaAppId) {
        this.mcpUrl = mcpUrl;
        this.httpClientProvider = httpClientProvider;
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
                "Lookup instructions for querying Okta users and groups via the MCP Gateway.",
                "Look up Okta users, groups, and group memberships",
                """
                # How to Use Okta Lookups

                ## Prerequisites
                - The "Okta MCP Admin" application assigned to you in Okta

                ## Steps

                1. **Log in to the MCP Gateway dashboard**
                   Open %s and sign in with Okta.

                2. **Check the Okta card**
                   If "Okta MCP Admin" is assigned to you, the Okta card shows as available —
                   no separate connect step or per-user sign-in is needed, since lookups run
                   against a single org-wide Okta API token.

                3. **Verify in your MCP client**
                   Run `tools/list` — the `okta_*` tools will appear in the list.

                If the Okta card is missing, ask your admin to assign you the "Okta MCP Admin"
                application in Okta.

                ## Available capabilities
                - List and search Okta users
                - Get a single user's profile
                - List security blocks applied to a user
                - List and search Okta groups
                - Get a single group's details
                - List members and applications assigned to a group
                """.formatted(dashboardUrl));
    }

    @Override
    public boolean isConnected(String userId) {
        return !mcpUrl.isBlank();
    }

    @Override
    public boolean canHandle(String toolName) {
        return toolName != null && toolName.startsWith(TOOL_PREFIX);
    }

    @Override
    public List<ToolEntry> listTools(String userId) throws Exception {
        log.debug("Okta MCP listTools: connecting to {}", mcpUrl);
        try (McpClient client = buildClient()) {
            var specs = client.listTools();
            log.info("Fetched {} tools from Okta MCP", specs.size());
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
        } catch (Exception e) {
            log.warn("Okta MCP listTools: connection to {} failed: {}", mcpUrl, e.getMessage());
            throw e;
        }
    }

    @Override
    public ToolCallResult callTool(String userId, String toolName, Map<String, Object> arguments) throws Exception {
        log.debug("Okta MCP callTool: connecting to {} for tool={}", mcpUrl, toolName);
        try (McpClient client = buildClient()) {
            String argsJson = MAPPER.writeValueAsString(arguments);
            var request = ToolExecutionRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .name(toolName)
                    .arguments(argsJson)
                    .build();
            var result = client.executeTool(request);
            log.info("tools/call result isError={}", result.isError());
            return new ToolCallResult(result.resultText(), result.isError());
        } catch (Exception e) {
            log.warn("Okta MCP callTool: connection to {} failed for tool={}: {}", mcpUrl, toolName, e.getMessage());
            throw e;
        }
    }

    private McpClient buildClient() {
        var headers = new LinkedHashMap<String, String>();

        log.debug("Okta MCP buildClient: url={} headers={}", mcpUrl, headers.keySet());
        var transport = AkkaHttpMcpTransport.builder()
                .httpClientProvider(httpClientProvider)
                .url(mcpUrl)
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
