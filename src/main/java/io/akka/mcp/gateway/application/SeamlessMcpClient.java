package io.akka.mcp.gateway.application;

import akka.javasdk.client.ComponentClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpException;
import dev.langchain4j.mcp.client.McpTextResourceContents;
import dev.langchain4j.mcp.client.McpToolMetadataKeys;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import io.akka.mcp.gateway.domain.McpConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SeamlessMcpClient implements RemoteMcpClient {

    public static final String MCP_ID = "seamless";
    public static final String MCP_NAME = "Seamless.AI";
    private static final String TOOL_PREFIX = "Seamless_";
    private static final String READ_RESOURCE_TOOL = TOOL_PREFIX + "read_resource";
    private static final String RESOURCE_URI_PREFIX = "seamless://";
    private static final String READ_RESOURCE_DESCRIPTION =
            "Read a read-only Seamless.AI reference resource by URI. Read seamless://credits before research, "
            + "and seamless://email-accounts, seamless://templates and seamless://templates/variables before "
            + "creating campaigns or sending email. Also available: seamless://me, seamless://connect/config "
            + "and seamless://campaigns/{campaignId}.";

    private static final Logger log = LoggerFactory.getLogger(SeamlessMcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Function<String, String> tokenFetcher;
    private final Function<String, McpClient> clientFactory;
    private final String mcpUrl;
    private final String oktaAppId;

    public SeamlessMcpClient(ComponentClient componentClient, String mcpUrl, String oktaAppId) {
        this(
                userId -> componentClient
                        .forKeyValueEntity(userId)
                        .method(SeamlessConnectionEntity::getAccessToken)
                        .invoke(),
                token -> buildClient(mcpUrl, token),
                mcpUrl, oktaAppId);
    }

    /** Test seam: supplies the user's access token and the upstream MCP client. */
    SeamlessMcpClient(Function<String, String> tokenFetcher, Function<String, McpClient> clientFactory,
                      String mcpUrl, String oktaAppId) {
        this.tokenFetcher = tokenFetcher;
        this.clientFactory = clientFactory;
        this.mcpUrl = mcpUrl;
        this.oktaAppId = oktaAppId;
    }

    @Override public String getMcpId() { return MCP_ID; }
    @Override public String getMcpName() { return MCP_NAME; }
    @Override public String getRequiredOktaAppId() { return oktaAppId; }

    @Override
    public HowToContent howTo(String dashboardUrl) {
        return new HowToContent(
                "Step-by-step instructions for connecting your Seamless.AI account to the MCP Gateway.",
                "Search and research contacts and companies, run campaigns, send email, log calls, manage tasks",
                """
                # How to Connect Seamless.AI

                ## Prerequisites
                - A Seamless.AI account with MCP access enabled for your organization
                - Your Okta SSO login for the MCP Gateway

                ## Steps

                1. **Log in to the MCP Gateway dashboard**
                   Open %s and sign in with Okta.

                2. **Go to the Seamless.AI section**
                   On the dashboard you will see a "Seamless.AI" card showing *Not connected*.

                3. **Click "Connect Seamless.AI"**
                   You will be redirected to Seamless.AI to sign in.

                4. **Authorise the application**
                   Sign in with your Seamless.AI account and approve access.

                5. **Return to the dashboard**
                   You are redirected back automatically. The Seamless.AI card now shows *Connected*.

                6. **Verify in your MCP client**
                   Run `tools/list` — Seamless.AI tools (prefixed `Seamless_`) will appear in the list.

                ## Available capabilities
                - Search contacts and companies, and research them to get contact details
                - Manage lists and saved searches
                - Campaigns, email templates, drafts and sending, call logging and tasks (requires the Seamless.AI Connect licence)

                ## Troubleshooting
                - "MCP Server access is not enabled for your account": ask your Seamless.AI administrator to enable MCP access.
                - Missing campaign, email, call or task tools: your organization may not have the Seamless.AI Connect licence.
                - If tools stop working, reconnect Seamless.AI from the dashboard.
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
        try (McpClient client = clientFactory.apply(token)) {
            var specs = client.listTools();
            log.info("Fetched {} tools from Seamless.AI MCP", specs.size());
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
                var meta = new McpConfig.ToolMeta(prefixedName, spec.description(), inputSchema, readOnlyHint, hasBodyParam);
                entries.add(new ToolEntry(tool, meta));
            }
            entries.add(readResourceToolEntry());
            return entries;
        }
    }

    /** Gateway-provided tool: the gateway only proxies tools, so resources are read through this one. */
    private static ToolEntry readResourceToolEntry() {
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", Map.of("uri", Map.of(
                "type", "string",
                "description", "Resource URI, e.g. seamless://credits (must start with seamless://)")));
        inputSchema.put("required", List.of("uri"));

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", READ_RESOURCE_TOOL);
        tool.put("description", READ_RESOURCE_DESCRIPTION);
        tool.put("inputSchema", inputSchema);
        tool.put("annotations", Map.of(McpToolMetadataKeys.READ_ONLY_HINT, true));
        return new ToolEntry(tool, new McpConfig.ToolMeta(READ_RESOURCE_TOOL, READ_RESOURCE_DESCRIPTION, inputSchema, true, false));
    }

    @Override
    public ToolCallResult callTool(String userId, String toolName, Map<String, Object> arguments) throws Exception {
        if (READ_RESOURCE_TOOL.equals(toolName)) {
            Object uri = arguments == null ? null : arguments.get("uri");
            if (!(uri instanceof String resourceUri) || !resourceUri.startsWith(RESOURCE_URI_PREFIX)) {
                return new ToolCallResult(
                        "Invalid or missing `uri`: it must be a string starting with " + RESOURCE_URI_PREFIX
                                + " (for example seamless://credits).", true);
            }
            return callUpstream(userId, client -> {
                var contents = client.readResource(resourceUri).contents();
                var text = contents.stream()
                        .filter(McpTextResourceContents.class::isInstance)
                        .map(c -> ((McpTextResourceContents) c).text())
                        .collect(Collectors.joining("\n"));
                return new ToolCallResult(text, false);
            });
        }

        String upstreamName = toolName.startsWith(TOOL_PREFIX) ? toolName.substring(TOOL_PREFIX.length()) : toolName;
        return callUpstream(userId, client -> {
            String argsJson = MAPPER.writeValueAsString(arguments);
            var request = ToolExecutionRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .name(upstreamName)
                    .arguments(argsJson)
                    .build();
            var result = client.executeTool(request);
            log.info("tools/call result isError={}", result.isError());
            return new ToolCallResult(result.resultText(), result.isError());
        });
    }

    private interface UpstreamCall {
        ToolCallResult run(McpClient client) throws Exception;
    }

    /**
     * Runs a call against Seamless as the user. A missing/expired connection propagates as an
     * exception (the gateway relays its message); anything that goes wrong upstream becomes an
     * error result the user can read.
     */
    private ToolCallResult callUpstream(String userId, UpstreamCall call) {
        String token = fetchToken(userId);
        try (McpClient client = clientFactory.apply(token)) {
            return call.run(client);
        } catch (Exception e) {
            log.warn("Seamless.AI upstream call failed: {}", e.toString());
            return new ToolCallResult(upstreamFailureMessage(e), true);
        }
    }

    private static String upstreamFailureMessage(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IOException || t instanceof TimeoutException) {
                return "Seamless.AI is temporarily unavailable. Please try again shortly.";
            }
        }
        String detail = failure instanceof McpException mcp && mcp.errorMessage() != null
                ? mcp.errorMessage()
                : failure.getMessage();
        return "Seamless.AI request failed: " + (detail != null ? detail : failure.getClass().getSimpleName());
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
        return tokenFetcher.apply(userId);
    }

    private static McpClient buildClient(String mcpUrl, String token) {
        var transport = new StreamableHttpMcpTransport.Builder()
                .url(mcpUrl)
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
