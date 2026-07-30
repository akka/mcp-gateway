package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.http.HttpClientProvider;
import akka.javasdk.http.HttpResponses;
import akka.javasdk.client.ComponentClient;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.AkkaSalesforceMcpClient;
import io.akka.mcp.gateway.application.GmailMcpClient;
import io.akka.mcp.gateway.application.GoogleCalendarMcpClient;
import io.akka.mcp.gateway.application.GoogleDriveMcpClient;
import io.akka.mcp.gateway.application.GroundcoverMcpClient;
import io.akka.mcp.gateway.application.HowToMcpClient;
import io.akka.mcp.gateway.application.HubspotMcpClient;
import io.akka.mcp.gateway.application.McpInteractionEntity;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.application.OktaMcpClient;
import io.akka.mcp.gateway.application.RemoteMcpClient;
import io.akka.mcp.gateway.application.ReoMcpClient;
import io.akka.mcp.gateway.application.SalesforceMcpClient;
import io.akka.mcp.gateway.application.SlackMcpClient;
import io.akka.mcp.gateway.application.ZohoMcpClient;
import io.akka.mcp.gateway.domain.McpConfig;
import io.akka.mcp.gateway.domain.UserSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Our main mcp proxy endpoint.
 *
 * Note: this is NOT an @McpEndpoint form Akka.
 * It's a plain http endpoint that behaves like MCP.
 */

@HttpEndpoint("/mcp")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class AkkaMcpGateway extends AbstractProtectedEndpoint {

    private static final Logger log = LoggerFactory.getLogger(AkkaMcpGateway.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Per-client budget for the live tools/list fetch. Fetches run in parallel and each is
    // bounded by this, so one slow/hung upstream can't stall the aggregate list (or push the
    // whole response past the MCP client's own request timeout, which surfaces as "no tools").
    private static final long LIVE_FETCH_TIMEOUT_SECONDS = 20;
    // Virtual threads: cheap, daemon by default, no pool sizing — a natural fit for the blocking
    // network I/O of the upstream tool fetches.
    private static final ExecutorService TOOLS_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final List<RemoteMcpClient> clients;

    // later we have to add some more dynamics here, who sees which client or so
    public AkkaMcpGateway(ComponentClient componentClient, HttpClientProvider httpClientProvider, Config config) {
        super(componentClient, config);
        var serviceClients = List.<RemoteMcpClient>of(
                new ZohoMcpClient(componentClient, config.getString("zoho.mcp-url"), config.getString("zoho.okta-app-id")),
                new GoogleDriveMcpClient(componentClient, config.getString("google-drive.mcp-url"), config.getString("google-drive.okta-app-id")),
                new SalesforceMcpClient(componentClient, config.getString("salesforce.mcp-url"), config.getString("salesforce.okta-app-id")),
                new AkkaSalesforceMcpClient(componentClient, httpClientProvider, config.getString("akka-salesforce.mcp-url"), config.getString("akka-salesforce.okta-app-id")),
                new ReoMcpClient(componentClient, config.getString("reo.mcp-url"), config.getString("reo.okta-app-id")),
                new GroundcoverMcpClient(componentClient, config.getString("groundcover.mcp-url"), config.getString("groundcover.okta-app-id")),
                new SlackMcpClient(componentClient, httpClientProvider, config.getString("slack.mcp-url"), config.getString("slack.okta-app-id")),
                new GmailMcpClient(componentClient, config.getString("gmail.mcp-url"), config.getString("gmail.okta-app-id")),
                new GoogleCalendarMcpClient(componentClient, config.getString("google-calendar.mcp-url"), config.getString("google-calendar.okta-app-id")),
                new HubspotMcpClient(componentClient, config.getString("hubspot.mcp-url"), config.getString("hubspot.okta-app-id")),
                new OktaMcpClient(config.getString("okta-admin.mcp-url"), httpClientProvider, config.getString("okta-admin.okta-app-id"))
        );
        this.clients = new ArrayList<>(serviceClients);
        this.clients.add(new HowToMcpClient(
                config.getString("mcp.base-url"),
                config.getString("support.email"),
                config.getString("support.slack-channel"),
                serviceClients));
    }

    /**
     * MCP JSON-RPC 2.0 dispatcher. Spec: https://modelcontextprotocol.io/specification/2024-11-05
     *
     * Incoming request shape:
     * <pre>
     * { "jsonrpc": "2.0", "id": 1, "method": "tools/call",
     *   "params": { "name": "query_accounts", "arguments": { "limit": 10 } } }
     * </pre>
     *
     * Handled methods: initialize, notifications/initialized, tools/list, tools/call, ping
     */
    public record McpAccessEntry(String mcpId, String mcpName) {}
    public record McpAccessResponse(List<McpAccessEntry> accessible, List<McpAccessEntry> inaccessible) {}

    @Get("/access")
    public HttpResponse mcpAccess() {
        var session = requireSession();
        if (session == null) return unauthorized();
        var accessible = new ArrayList<McpAccessEntry>();
        var inaccessible = new ArrayList<McpAccessEntry>();
        for (var client : clients) {
            if (client.getMcpId().equals(HowToMcpClient.MCP_ID)) continue;
            var required = client.getRequiredOktaAppId();
            // match on the stable Okta app id, never the display label
            var hasAccess = required.isBlank() || session.hasApp(required);
            var entry = new McpAccessEntry(client.getMcpId(), client.getMcpName());
            if (hasAccess) accessible.add(entry); else inaccessible.add(entry);
        }
        return HttpResponses.ok(new McpAccessResponse(accessible, inaccessible));
    }

    @Post("")
    public HttpResponse handleMcp(HttpEntity.Strict rawBody) {
        var session = requireSession();
        if (session == null) return unauthorizedForMcp();

        String body = rawBody.getData().utf8String();
        log.debug(">>> POST /proxy body={}", body);

        Map<String, Object> request;
        try {
            request = MAPPER.readValue(body, Map.class);
        } catch (Exception e) {
            log.warn("JSON parse error: {}", e.getMessage());
            return jsonResponse(errorJson(null, -32700, "Parse error"));
        }

        Long id = extractId(request);
        String method = (String) request.getOrDefault("method", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", Map.of());

        log.info("MCP request: method={} id={}", method, id);

        String responseJson = switch (method) {
            case "initialize" -> handleInitialize(id);
            case "notifications/initialized" -> "{}";
            case "tools/list" -> handleToolsList(id, session.email());
            case "tools/call" -> handleToolsCall(id, params, session); // logs its own req+resp
            case "ping" -> responseJson(id, Map.of());
            default -> {
                log.warn("Unknown MCP method: {}", method);
                yield errorJson(id, -32601, "Method not found: " + method);
            }
        };

        // tools/call logs its own req+resp with the actual MCP backend id
        if (!method.equals("tools/call")) {
            componentClient
                    .forEventSourcedEntity(UUID.randomUUID().toString())
                    .method(McpInteractionEntity::record)
                    .invoke(new McpInteractionEntity.RecordCommand(
                            session.email(), "proxy", method, Map.of("params", toJson(params)), "req"));
            componentClient
                    .forEventSourcedEntity(UUID.randomUUID().toString())
                    .method(McpInteractionEntity::record)
                    .invoke(new McpInteractionEntity.RecordCommand(
                            session.email(), "proxy", method, Map.of("response", responseJson), "resp"));
        }

        return jsonResponse(responseJson);
    }

    private String handleInitialize(Long id) {
        log.info("MCP initialize");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("capabilities", Map.of("tools", Map.of("listChanged", true)));
        result.put("serverInfo", Map.of("name", "proxy", "version", "1.0.0"));
        return responseJson(id, result);
    }

    private String handleToolsList(Long id, String userId) {
        log.info("MCP tools/list: aggregating from {} clients", clients.size());
        List<Map<String, Object>> allTools = new ArrayList<>();

        // 1. Local how-to tools first and unconditionally. They are built in-memory, so they can
        //    never fail or block — this guarantees the list is never empty and that
        //    `howto_refresh_tools` is always present, even if every upstream is down.
        for (var client : clients) {
            if (client.getMcpId().equals(HowToMcpClient.MCP_ID)) {
                addLocalTools(client, userId, allTools);
            }
        }

        // 2. Service clients. Kick off every live fetch in parallel so total latency is bounded by
        //    the slowest single upstream, not their sum. Each contribution is isolated: any
        //    failure, timeout, or empty result degrades to that client's last-known cached tools
        //    rather than aborting the whole response.
        Map<RemoteMcpClient, Future<List<RemoteMcpClient.ToolEntry>>> pending = new LinkedHashMap<>();
        for (var client : clients) {
            if (client.getMcpId().equals(HowToMcpClient.MCP_ID)) continue;
            var clientName = client.getClass().getSimpleName();
            try {
                if (client.isConnected(userId)) {
                    log.info("MCP tools/list: fetching live tools from {}", clientName);
                    pending.put(client, TOOLS_EXECUTOR.submit(() -> client.listTools(userId)));
                } else {
                    log.info("MCP tools/list: {} disconnected, using registry cache", clientName);
                    addCachedTools(client.getMcpId(), allTools);
                }
            } catch (Exception e) {
                log.error("MCP tools/list: could not start fetch for {}: {} — using cache", clientName, e.getMessage(), e);
                addCachedTools(client.getMcpId(), allTools);
            }
        }

        for (var entry : pending.entrySet()) {
            var client = entry.getKey();
            var clientName = client.getClass().getSimpleName();
            List<RemoteMcpClient.ToolEntry> entries;
            try {
                entries = entry.getValue().get(LIVE_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                entry.getValue().cancel(true);
                log.error("MCP tools/list: live fetch failed/timed out for {}: {} — falling back to cache",
                        clientName, e.getMessage());
                addCachedTools(client.getMcpId(), allTools);
                continue;
            }
            if (entries.isEmpty()) {
                // Connected but returned nothing (transient upstream hiccup): keep the last-known
                // cache rather than overwriting it with an empty list.
                log.warn("MCP tools/list: {} returned 0 live tools — falling back to cache", clientName);
                addCachedTools(client.getMcpId(), allTools);
                continue;
            }
            log.info("MCP tools/list: got {} live tools from {}", entries.size(), clientName);
            var toolMetas = entries.stream().map(RemoteMcpClient.ToolEntry::meta).toList();
            try {
                componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                        .method(McpRegistryEntity::register)
                        .invoke(new McpConfig(client.getMcpId(), client.getMcpName(), toolMetas));
            } catch (Exception e) {
                log.error("MCP tools/list: failed to cache tools for {}: {}", clientName, e.getMessage());
            }
            entries.forEach(e -> allTools.add(e.toolSpec()));
        }

        log.info("MCP tools/list: returning {} tools total", allTools.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", allTools);
        return responseJson(id, result);
    }

    /** Add tools from a local, in-memory client (the how-to client) without a network fetch. */
    private void addLocalTools(RemoteMcpClient client, String userId, List<Map<String, Object>> allTools) {
        try {
            var entries = client.listTools(userId);
            entries.forEach(e -> allTools.add(e.toolSpec()));
        } catch (Exception e) {
            log.error("MCP tools/list: failed to add local tools from {}: {}",
                    client.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    /** Best-effort: append a client's last-known cached tools. Never throws. */
    private void addCachedTools(String mcpId, List<Map<String, Object>> allTools) {
        try {
            var cached = componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                    .method(McpRegistryEntity::findByMcpId)
                    .invoke(mcpId);
            cached.ifPresent(cfg -> {
                log.info("MCP tools/list: adding {} cached tools for {}", cfg.tools().size(), mcpId);
                cfg.tools().forEach(meta -> allTools.add(meta.toToolSpec()));
            });
        } catch (Exception e) {
            log.error("MCP tools/list: cache lookup failed for {}: {}", mcpId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String handleToolsCall(Long id, Map<String, Object> params, UserSession session) {
        String userEmail = session.email();
        String toolName = (String) params.get("name");
        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());

        if (toolName == null || toolName.isBlank()) {
            return errorJson(id, -32602, "Missing tool name");
        }

        var registeredMcpId = componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::findMcpIdForTool)
                .invoke(toolName);

        // Find the owning client regardless of connection status
        var client = registeredMcpId
                .flatMap(mcpId -> clients.stream()
                        .filter(c -> c.getMcpId().equals(mcpId))
                        .findFirst())
                .or(() -> clients.stream()
                        .filter(c -> c.canHandle(toolName))
                        .findFirst())
                .orElse(null);

        if (client == null) {
            log.warn("No client can handle tool: {}", toolName);
            componentClient
                    .forEventSourcedEntity(UUID.randomUUID().toString())
                    .method(McpInteractionEntity::record)
                    .invoke(new McpInteractionEntity.RecordCommand(
                            userEmail, "proxy", toolName, Map.of("error", "no-client-for-tool"), "resp"));
            return errorJson(id, -32601, "No MCP client can handle tool: " + toolName);
        }

        // Return a friendly error when the client exists but the user hasn't connected it.
        // This check must come before the write guard: on cold-start the registry is empty,
        // so toolMeta would be absent and isWrite would default to true — producing a
        // misleading "write not permitted" error when the real issue is "not connected".
        if (!client.isConnected(userEmail)) {
            String howtoTool = "howto_connect_" + client.getMcpId().replace("-", "_");
            String msg = client.getMcpName() + " is not connected. "
                    + "Call `" + howtoTool + "` for setup instructions, or visit the dashboard to connect.";
            log.info("tools/call: {} not connected for user {}", client.getMcpName(), userEmail);
            componentClient
                    .forEventSourcedEntity(UUID.randomUUID().toString())
                    .method(McpInteractionEntity::record)
                    .invoke(new McpInteractionEntity.RecordCommand(
                            userEmail, client.getMcpId(), toolName, Map.of("error", "not-connected"), "resp"));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("content", List.of(Map.of("type", "text", "text", msg)));
            resp.put("isError", true);
            return responseJson(id, resp);
        }

        var toolMeta = componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::findTool)
                .invoke(toolName);
        boolean isWrite = toolMeta
                .map(McpConfig.ToolMeta::isWrite)
                .orElse(true); // unknown → assume write (safe default)
        log.info("MCP tools/call: name={} opType={}", toolName, isWrite ? "write" : "read");


        boolean isRead = !isWrite;
        String label = isRead ? "Read": "Write";
        boolean canInteract = session.canInteract(isWrite, readerGroup, writerGroup);
        if (!canInteract) {
            log.warn("MCP tools/call: {} access rejected for user {}: {}, read={}, write={}", label, userEmail, toolName, session.canRead(readerGroup), session.canWrite(writerGroup));
            componentClient
                    .forEventSourcedEntity(UUID.randomUUID().toString())
                    .method(McpInteractionEntity::record)
                    .invoke(new McpInteractionEntity.RecordCommand(
                            userEmail, "proxy", toolName, Map.of("reason", "read-not-permitted"), "read-rejected"));
            return errorJson(id, -32603, label + " access not permitted");
        }

        componentClient
                .forEventSourcedEntity(UUID.randomUUID().toString())
                .method(McpInteractionEntity::record)
                .invoke(new McpInteractionEntity.RecordCommand(
                        userEmail, client.getMcpId(), toolName, Map.of("arguments", toJson(arguments)), "req"));

        try {
            var result = client.callTool(userEmail, toolName, arguments);
            String truncatedOutput = result.text() != null && result.text().length() > 4000
                    ? result.text().substring(0, 4000) + "… [truncated]"
                    : result.text();
            componentClient
                    .forEventSourcedEntity(UUID.randomUUID().toString())
                    .method(McpInteractionEntity::record)
                    .invoke(new McpInteractionEntity.RecordCommand(
                            userEmail, client.getMcpId(), toolName, Map.of("isError", String.valueOf(result.isError())), "resp", truncatedOutput));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("content", List.of(Map.of("type", "text", "text", result.text())));
            resp.put("isError", result.isError());
            return responseJson(id, resp);
        } catch (Exception e) {
            log.error("tools/call failed: {}", e.getMessage(), e);
            componentClient
                    .forEventSourcedEntity(UUID.randomUUID().toString())
                    .method(McpInteractionEntity::record)
                    .invoke(new McpInteractionEntity.RecordCommand(
                            userEmail, client.getMcpId(), toolName,
                            Map.of("error", e.getMessage() != null ? e.getMessage() : "unknown"), "resp"));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("content", List.of(Map.of("type", "text", "text",
                    e.getMessage() != null ? e.getMessage() : "Unknown error")));
            resp.put("isError", true);
            return responseJson(id, resp);
        }
    }

    // -- static helpers --

    private static Long extractId(Map<String, Object> request) {
        Object raw = request.get("id");
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static String responseJson(Long id, Map<String, Object> result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", result);
        try {
            return MAPPER.writeValueAsString(resp);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}";
        }
    }

    private static String errorJson(Long id, int code, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("error", Map.of("code", code, "message", message));
        try {
            return MAPPER.writeValueAsString(resp);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}";
        }
    }

    private static HttpResponse jsonResponse(String json) {
        return HttpResponse.create()
                .withStatus(200)
                .withEntity(ContentTypes.APPLICATION_JSON, json);
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
