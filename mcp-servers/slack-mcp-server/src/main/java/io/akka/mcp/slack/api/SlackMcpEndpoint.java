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
import io.akka.mcp.slack.application.SlackDraftClient;
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
 * Every tool is read-only (readOnlyHint: true) except slack_post_message and slack_draft_message, which are advertised with
 * readOnlyHint: false so the gateway applies its write gate to it. The token is the user's own
 * OAuth token so they can only access channels and data they normally can see.
 */
@HttpEndpoint("/mcp")
@Acl(allow = @Acl.Matcher(service = "mcp-gateway"))
public class SlackMcpEndpoint extends AbstractHttpEndpoint {

    private static final Logger log = LoggerFactory.getLogger(SlackMcpEndpoint.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String slackApiBaseUrl;
    private final String hostedMcpUrl;

    public SlackMcpEndpoint(Config config) {
        this.slackApiBaseUrl = config.getString("slack.api-base-url");
        this.hostedMcpUrl = config.getString("slack.hosted-mcp-url");
    }

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

        tools.add(writeTool("slack_post_message",
                "Post a message to a Slack channel, DM, or thread. The message is prefixed with 🤖 automatically so "
                        + "readers can tell an assistant posted it; set omit_assistant_marker only if the user has "
                        + "explicitly told you not to mark this message. Messages to channels shared outside Akka "
                        + "(Slack Connect or external- channels) are never posted: you get the text back for the user "
                        + "to review and send themselves. "
                        + "Requires the chat:write scope; "
                        + "if the user connected before this scope was requested they need to reconnect.",
                props(
                        param("channel", "string",
                                "Channel/DM/group id (e.g. C12345, D12345, G12345). Use slack_list_channels or slack_search_messages to find it; @name and #name are not accepted."),
                        param("text", "string", "Message body (Slack mrkdwn supported)."),
                        param("thread_ts", "string",
                                "Optional parent message timestamp (e.g. 1234567890.123456). Omit to post as a new top-level message."),
                        param("omit_assistant_marker", "boolean",
                                "Leave unset. Set to true only when the user has explicitly asked for this message not to be marked as posted by an assistant.")),
                List.of("channel", "text")));

        tools.add(writeTool("slack_draft_message",
                "Save a draft message in the user's Slack (Drafts & Sent) for them to review, edit and send "
                        + "themselves. Nothing is sent. Prefer this over slack_post_message whenever a person should "
                        + "read the words first, and always for channels shared with customers or partners. "
                        + "Slack allows one draft per channel: if one exists, ask the user to send or delete it first.",
                props(
                        param("channel", "string",
                                "Channel/DM/group id (e.g. C12345, D12345, G12345), or a user id for a DM. Use slack_list_channels or slack_search_messages to find it; @name and #name are not accepted."),
                        param("text", "string", "Draft body (markdown supported)."),
                        param("thread_ts", "string",
                                "Optional parent message timestamp (e.g. 1234567890.123456) to draft a thread reply. Omit for a new top-level message.")),
                List.of("channel", "text")));

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
        var slack = new SlackApiClient(token, slackApiBaseUrl);

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
                case "slack_post_message" -> {
                    String channel = required(args, "channel");
                    String messageText = required(args, "text");
                    String threadTs = str(args, "thread_ts");
                    boolean markAsAssistant = !boolArg(args, "omit_assistant_marker");
                    var target = channelOrNotPosted(slack, channel);
                    if (target.external()) throw new NotPostedException(externalChannelNotPosted(target, messageText, threadTs));
                    yield MAPPER.writeValueAsString(slack.postMessage(channel, messageText, threadTs, markAsAssistant));
                }
                case "slack_draft_message" -> {
                    String channel = required(args, "channel");
                    String draftText = required(args, "text");
                    String threadTs = str(args, "thread_ts");
                    yield new SlackDraftClient(token, hostedMcpUrl).createDraft(channel, draftText, threadTs);
                }
                default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
            };

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of("type", "text", "text", text)));
            result.put("isError", false);
            return responseJson(id, result);

        } catch (NotPostedException e) {
            log.info("tools/call {} not posted", toolName);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of("type", "text", "text", e.getMessage())));
            result.put("isError", true);
            return responseJson(id, result);

        } catch (Exception e) {
            log.warn("tools/call {} failed: {}", toolName, e.getMessage());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of(Map.of("type", "text", "text", e.getMessage())));
            result.put("isError", true);
            return responseJson(id, result);
        }
    }

    /** A post that was deliberately not made. The message may carry the user's text, so it is never logged. */
    private static final class NotPostedException extends RuntimeException {
        NotPostedException(String message) { super(message); }
    }

    /** If the channel cannot be checked it is not posted to: an unknown channel might be external. */
    private static SlackApiClient.Channel channelOrNotPosted(SlackApiClient slack, String channel) throws Exception {
        try {
            return slack.channelInfo(channel);
        } catch (SlackApiClient.SlackApiException e) {
            throw new NotPostedException("Not posted: could not check whether this conversation is shared outside Akka ("
                    + e.slackError() + "). Nothing was sent. To let the user review the message, save it with "
                    + "slack_draft_message instead.");
        }
    }

    private static String externalChannelNotPosted(SlackApiClient.Channel target, String text, String threadTs) {
        var where = target.name().isBlank() ? "This conversation" : "#" + target.name();
        var thread = threadTs == null || threadTs.isBlank() ? "" : " and thread_ts=" + threadTs;
        return "Not posted: " + where + " is shared outside Akka, and an assistant never posts there. A person reviews "
                + "and sends messages to external channels, without the assistant marker. Save it as a draft for the "
                + "user by calling slack_draft_message with the same channel" + thread + " and this text, then tell "
                + "the user to review and send it from Drafts & Sent. If the draft cannot be saved, give the user "
                + "this message to review and send themselves:\n\n" + text;
    }

    // -- tool schema helpers --

    private static Map<String, Object> tool(String name, String description,
            Map<String, Object> properties, List<String> required) {
        return toolInternal(name, description, properties, required, Map.of("readOnlyHint", true));
    }

    /** A tool that mutates state in Slack. The {@code readOnlyHint:false} is what the gateway's
     * write classifier reads to send the call through the write-permission gate. */
    private static Map<String, Object> writeTool(String name, String description,
            Map<String, Object> properties, List<String> required) {
        return toolInternal(name, description, properties, required,
                Map.of("readOnlyHint", false, "destructiveHint", false, "idempotentHint", false));
    }

    private static Map<String, Object> toolInternal(String name, String description,
            Map<String, Object> properties, List<String> required, Map<String, Object> annotations) {
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        if (!required.isEmpty()) inputSchema.put("required", required);

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

    private static boolean boolArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v instanceof Boolean b) return b;
        return v != null && Boolean.parseBoolean(v.toString());
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
