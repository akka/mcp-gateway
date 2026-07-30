package io.akka.mcp.okta.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.http.AbstractHttpEndpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import io.akka.mcp.okta.application.OktaApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP JSON-RPC endpoint backed by the Okta Management API (users, groups).
 *
 * Authenticates to Okta with a single org-wide SSWS API token configured for this
 * service (OKTA_ORG_URL / OKTA_API_TOKEN env vars), since Okta admin API tokens are
 * org-scoped rather than per end user.
 *
 * All tools are read-only (readOnlyHint: true).
 */
@HttpEndpoint("/mcp")
@Acl(allow = @Acl.Matcher(service = "mcp-gateway"))
public class OktaMcpEndpoint extends AbstractHttpEndpoint {

    private static final Logger log = LoggerFactory.getLogger(OktaMcpEndpoint.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Endpoint instances are created per request; the Okta ApiClient owns a pooled
    // HTTP client and is safe to share, so it's built once per JVM rather than per call.
    private static final OktaApiClient okta =
            new OktaApiClient(requireEnv("OKTA_ORG_URL"), requireEnv("OKTA_API_TOKEN"));

    @Post("")
    public HttpResponse handle(HttpEntity.Strict rawBody) {
        log.debug("Incoming MCP request, bodyBytes={}", rawBody.getData().size());

        String body = rawBody.getData().utf8String();
        log.debug("MCP request: {}", body);

        Map<String, Object> req;
        try {
            req = MAPPER.readValue(body, Map.class);
        } catch (Exception e) {
            return jsonResponse(errorJson(null, -32700, "Parse error"));
        }

        Long id = extractId(req);
        String method = (String) req.getOrDefault("method", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) req.getOrDefault("params", Map.of());

        log.info("MCP method={} id={}", method, id);

        String response = switch (method) {
            case "initialize" -> handleInitialize(id);
            case "notifications/initialized" -> "{}";
            case "tools/list" -> handleToolsList(id);
            case "tools/call" -> handleToolsCall(id, params);
            case "ping" -> responseJson(id, Map.of());
            default -> errorJson(id, -32601, "Method not found: " + method);
        };

        return jsonResponse(response);
    }

    // -- initialize --

    private String handleInitialize(Long id) {
        return responseJson(id, Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "okta-mcp-server", "version", "1.0.0")));
    }

    // -- tools/list --

    private String handleToolsList(Long id) {
        var tools = new ArrayList<Map<String, Object>>();

        tools.add(tool("okta_list_users",
                "List/search Okta users in the org. Returns id, status, login, email, name, and timestamps.",
                props(
                        param("q", "string", "Simple query matched against firstName, lastName, or email (startsWith match)"),
                        param("filter", "string", "Okta filter expression, e.g. status eq \"ACTIVE\""),
                        param("search", "string", "Okta search expression (SCIM-like) for advanced queries"),
                        param("limit", "integer", "Max users to return (1-200, default 200)")),
                List.of()));

        tools.add(tool("okta_get_user",
                "Get a single Okta user by ID or login. Returns full profile fields (login, email, name, title, department, manager, etc.).",
                props(param("user_id", "string", "The Okta user ID or login (e.g. jane@example.com)")),
                List.of("user_id")));

        tools.add(tool("okta_list_user_blocks",
                "List the network zone block types currently applied to an Okta user (security/lockout information).",
                props(param("user_id", "string", "The Okta user ID or login")),
                List.of("user_id")));

        tools.add(tool("okta_list_groups",
                "List/search Okta groups in the org. Returns id, name, description, type, and timestamps.",
                props(
                        param("q", "string", "Simple query matched against group name (startsWith match)"),
                        param("filter", "string", "Okta filter expression, e.g. type eq \"OKTA_GROUP\""),
                        param("search", "string", "Okta search expression for advanced queries"),
                        param("limit", "integer", "Max groups to return (1-200, default 200)")),
                List.of()));

        tools.add(tool("okta_get_group",
                "Get a single Okta group by ID. Returns name, description, type, and timestamps.",
                props(param("group_id", "string", "The Okta group ID")),
                List.of("group_id")));

        tools.add(tool("okta_list_group_members",
                "List the users that belong to an Okta group.",
                props(
                        param("group_id", "string", "The Okta group ID"),
                        param("limit", "integer", "Max users to return (1-200, default 200)")),
                List.of("group_id")));

        tools.add(tool("okta_list_group_applications",
                "List the applications assigned to an Okta group.",
                props(
                        param("group_id", "string", "The Okta group ID"),
                        param("limit", "integer", "Max applications to return (1-200, default 200)")),
                List.of("group_id")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", tools);
        return responseJson(id, result);
    }

    // -- tools/call --

    @SuppressWarnings("unchecked")
    private String handleToolsCall(Long id, Map<String, Object> params) {
        String toolName = (String) params.get("name");
        Map<String, Object> args = (Map<String, Object>) params.getOrDefault("arguments", Map.of());

        if (toolName == null || toolName.isBlank()) {
            return errorJson(id, -32602, "Missing tool name");
        }

        log.info("tools/call: {}", toolName);

        try {
            String text = switch (toolName) {
                case "okta_list_users" -> {
                    String q = str(args, "q");
                    String filter = str(args, "filter");
                    String search = str(args, "search");
                    int limit = intArg(args, "limit", 200);
                    yield MAPPER.writeValueAsString(okta.listUsers(q, filter, search, limit));
                }
                case "okta_get_user" -> {
                    String userId = required(args, "user_id");
                    yield MAPPER.writeValueAsString(okta.getUser(userId));
                }
                case "okta_list_user_blocks" -> {
                    String userId = required(args, "user_id");
                    yield MAPPER.writeValueAsString(okta.listUserBlocks(userId));
                }
                case "okta_list_groups" -> {
                    String q = str(args, "q");
                    String filter = str(args, "filter");
                    String search = str(args, "search");
                    int limit = intArg(args, "limit", 200);
                    yield MAPPER.writeValueAsString(okta.listGroups(q, filter, search, limit));
                }
                case "okta_get_group" -> {
                    String groupId = required(args, "group_id");
                    yield MAPPER.writeValueAsString(okta.getGroup(groupId));
                }
                case "okta_list_group_members" -> {
                    String groupId = required(args, "group_id");
                    int limit = intArg(args, "limit", 200);
                    yield MAPPER.writeValueAsString(okta.listGroupMembers(groupId, limit));
                }
                case "okta_list_group_applications" -> {
                    String groupId = required(args, "group_id");
                    int limit = intArg(args, "limit", 200);
                    yield MAPPER.writeValueAsString(okta.listGroupApplications(groupId, limit));
                }
                default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
            };

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of("type", "text", "text", text)));
            result.put("isError", false);
            return responseJson(id, result);

        } catch (Exception e) {
            log.warn("tools/call {} failed: {}", toolName, e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of("type", "text", "text", e.getMessage())));
            result.put("isError", true);
            return responseJson(id, result);
        }
    }

    // -- tool schema helpers --

    private static Map<String, Object> tool(String name, String description,
            Map<String, Object> properties, List<String> required) {
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        if (!required.isEmpty()) inputSchema.put("required", required);

        Map<String, Object> annotations = Map.of("readOnlyHint", true);

        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", inputSchema);
        t.put("annotations", annotations);
        return t;
    }

    private static Map<String, Object> props(Map<String, Object>... params) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (var p : params) props.putAll(p);
        return props;
    }

    private static Map<String, Object> param(String name, String type, String description) {
        return Map.of(name, Map.of("type", type, "description", description));
    }

    // -- arg helpers --

    private static String required(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || v.toString().isBlank()) throw new IllegalArgumentException("Missing required argument: " + key);
        return v.toString();
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v != null && !v.toString().isBlank() ? v.toString() : null;
    }

    private static int intArg(Map<String, Object> args, String key, int defaultValue) {
        Object v = args.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return defaultValue; }
    }

    // -- config --

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }

    // -- JSON-RPC helpers --

    private static Long extractId(Map<String, Object> req) {
        Object raw = req.get("id");
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s) { try { return Long.parseLong(s); } catch (NumberFormatException ignored) {} }
        return null;
    }

    private static String responseJson(Long id, Map<String, Object> result) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", result);
        try { return MAPPER.writeValueAsString(resp); }
        catch (Exception e) { return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}"; }
    }

    private static String errorJson(Long id, int code, String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("error", Map.of("code", code, "message", message));
        try { return MAPPER.writeValueAsString(resp); }
        catch (Exception e) { return "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}"; }
    }

    private static HttpResponse jsonResponse(String json) {
        return HttpResponse.create()
                .withStatus(200)
                .withEntity(ContentTypes.APPLICATION_JSON, json);
    }
}
