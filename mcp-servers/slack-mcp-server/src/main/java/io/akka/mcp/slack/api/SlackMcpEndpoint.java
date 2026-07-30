package io.akka.mcp.slack.api;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.http.AbstractHttpEndpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import io.akka.mcp.slack.application.SlackApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP JSON-RPC endpoint backed by the Slack Web API.
 *
 * Callers must supply the user's Slack token as a Bearer token:
 *   Authorization: Bearer xoxp-...
 *
 *
 * All tools are read-only (readOnlyHint: true). The token is the user's own
 * OAuth token so they can only access channels and data they normally can see.
 */
@HttpEndpoint("/mcp")
@Acl(allow = @Acl.Matcher(service = "mcp-gateway"))
public class SlackMcpEndpoint extends AbstractHttpEndpoint {

    private static final Logger log = LoggerFactory.getLogger(SlackMcpEndpoint.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Post("")
    public HttpResponse handle(HttpEntity.Strict rawBody) {

        String token = extractBearer();
        if (token == null) {
            return jsonResponse(errorJson(null, -32001, "Unauthorized: missing Bearer token"));
        }

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
            case "tools/call" -> handleToolsCall(id, params, token);
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
                "serverInfo", Map.of("name", "slack-mcp-server", "version", "1.0.0")));
    }

    // -- tools/list --

    private String handleToolsList(Long id) {
        var tools = new ArrayList<Map<String, Object>>();

        tools.add(tool("slack_list_channels",
                "List Slack channels the user is a member of (public, private, DMs, group DMs). Returns channel IDs, names, and member counts.",
                props(
                        param("cursor", "string", "Pagination cursor from a previous response"),
                        param("limit", "integer", "Max channels to return (1-200, default 100)")),
                List.of()));

        tools.add(tool("slack_read_channel",
                "Read recent messages from a Slack channel. Returns message text, author user IDs, timestamps, and reaction counts.",
                props(
                        param("channel_id", "string", "The channel ID (e.g. C12345)"),
                        param("limit", "integer", "Max messages to return (1-200, default 50)"),
                        param("oldest", "string", "Start of time range as Unix timestamp"),
                        param("latest", "string", "End of time range as Unix timestamp")),
                List.of("channel_id")));

        tools.add(tool("slack_read_thread",
                "Read all replies in a Slack message thread. Returns the parent message and all replies with author IDs and timestamps.",
                props(
                        param("channel_id", "string", "The channel ID the thread is in"),
                        param("thread_ts", "string", "The timestamp of the parent message (e.g. 1234567890.123456)"),
                        param("limit", "integer", "Max replies to return (1-200, default 100)")),
                List.of("channel_id", "thread_ts")));

        tools.add(tool("slack_get_file",
                "Get metadata and content of a file shared in Slack. Returns file name, type, uploader, sharing context, and a download URL.",
                props(param("file_id", "string", "The Slack file ID (e.g. F12345)")),
                List.of("file_id")));

        tools.add(tool("slack_get_user_profile",
                "Look up a Slack user's profile by user ID. Returns display name, real name, email, title, and timezone.",
                props(param("user_id", "string", "The Slack user ID (e.g. U12345)")),
                List.of("user_id")));

        tools.add(tool("slack_search_messages",
                "Full-text search across Slack messages the user has access to. Returns matching messages with channel context and permalinks.",
                props(
                        param("query", "string", "Search query (supports Slack modifiers like in:#channel, from:@user)"),
                        param("count", "integer", "Results per page (1-100, default 20)"),
                        param("page", "integer", "Page number (default 1)")),
                List.of("query")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", tools);
        return responseJson(id, result);
    }

    // -- tools/call --

    @SuppressWarnings("unchecked")
    private String handleToolsCall(Long id, Map<String, Object> params, String token) {
        String toolName = (String) params.get("name");
        Map<String, Object> args = (Map<String, Object>) params.getOrDefault("arguments", Map.of());

        if (toolName == null || toolName.isBlank()) {
            return errorJson(id, -32602, "Missing tool name");
        }

        log.info("tools/call: {}", toolName);
        var slack = new SlackApiClient(token);

        try {
            String text = switch (toolName) {
                case "slack_list_channels" -> {
                    String cursor = str(args, "cursor");
                    int limit = intArg(args, "limit", 100);
                    yield MAPPER.writeValueAsString(slack.listChannels(cursor, limit));
                }
                case "slack_read_channel" -> {
                    String channelId = required(args, "channel_id");
                    int limit = intArg(args, "limit", 50);
                    String oldest = str(args, "oldest");
                    String latest = str(args, "latest");
                    yield MAPPER.writeValueAsString(slack.channelHistory(channelId, oldest, latest, limit));
                }
                case "slack_read_thread" -> {
                    String channelId = required(args, "channel_id");
                    String threadTs = required(args, "thread_ts");
                    int limit = intArg(args, "limit", 100);
                    yield MAPPER.writeValueAsString(slack.threadReplies(channelId, threadTs, limit));
                }
                case "slack_get_file" -> {
                    String fileId = required(args, "file_id");
                    yield MAPPER.writeValueAsString(slack.fileInfo(fileId));
                }
                case "slack_get_user_profile" -> {
                    String userId = required(args, "user_id");
                    yield MAPPER.writeValueAsString(slack.userInfo(userId));
                }
                case "slack_search_messages" -> {
                    String query = required(args, "query");
                    int count = intArg(args, "count", 20);
                    int page = intArg(args, "page", 1);
                    yield MAPPER.writeValueAsString(slack.searchMessages(query, page, count));
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
        return v != null ? v.toString() : null;
    }

    private static int intArg(Map<String, Object> args, String key, int defaultValue) {
        Object v = args.get(key);
        if (v == null) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return defaultValue; }
    }

    // -- JWT/Bearer extraction --

    private String extractBearer() {
        return requestContext().requestHeader("Authorization")
                .map(h -> h.value())
                .filter(v -> v.toLowerCase().startsWith("bearer "))
                .map(v -> v.substring(7).trim())
                .orElse(null);
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
