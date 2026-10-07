package io.akka.mcp.slack.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Saves a draft in the user's Slack "Drafts & Sent" by calling the draft tool of Slack's hosted MCP
 * server with the user's own token. The Slack Web API has no way to create a draft, so this is the
 * only supported route. Stateless: the caller supplies the token per call.
 */
public class SlackDraftClient {

    static final String HOSTED_DRAFT_TOOL = "slack_send_message_draft";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String token;
    private final String hostedMcpUrl;

    public SlackDraftClient(String token, String hostedMcpUrl) {
        this.token = token;
        this.hostedMcpUrl = hostedMcpUrl;
    }

    /** Returns the hosted server's answer, which carries the link to the channel holding the draft. */
    public String createDraft(String channelId, String message, String threadTs) throws Exception {
        var arguments = MAPPER.createObjectNode();
        arguments.put("channel_id", channelId);
        arguments.put("message", message);
        if (threadTs != null && !threadTs.isBlank()) arguments.put("thread_ts", threadTs);

        var initialize = send(null, 1, "initialize", MAPPER.valueToTree(Map.of(
                "protocolVersion", "2025-03-26",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "akka-mcp-gateway", "version", "1.0.0"))));
        String sessionId = initialize.sessionId();
        failOnRpcError(initialize.body());

        send(sessionId, null, "notifications/initialized", null);

        var call = send(sessionId, 2, "tools/call", MAPPER.valueToTree(Map.of(
                "name", HOSTED_DRAFT_TOOL,
                "arguments", arguments)));
        failOnRpcError(call.body());

        JsonNode result = call.body().path("result");
        String text = result.path("content").path(0).path("text").asText("");
        if (result.path("isError").asBoolean(false)) throw new DraftException(explain(text));
        return text;
    }

    private record Reply(String sessionId, JsonNode body) {}

    private Reply send(String sessionId, Integer id, String method, JsonNode params) throws Exception {
        var request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        if (id != null) request.put("id", id);
        request.put("method", method);
        if (params != null) request.set("params", params);

        var builder = HttpRequest.newBuilder()
                .uri(URI.create(hostedMcpUrl))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(request), StandardCharsets.UTF_8));
        if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);

        var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String raw = response.body() == null ? "" : response.body().trim();
        JsonNode body = raw.isEmpty() ? MAPPER.createObjectNode() : parse(raw);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new DraftException("Slack refused the connection (HTTP " + response.statusCode()
                    + "). Disconnect and reconnect Slack on the gateway dashboard.");
        }
        return new Reply(response.headers().firstValue("Mcp-Session-Id").orElse(sessionId), body);
    }

    /** The hosted server answers with plain JSON or, as a stream, with the JSON in the last {@code data:} line. */
    private static JsonNode parse(String raw) throws Exception {
        if (raw.startsWith("{")) return MAPPER.readTree(raw);
        String lastData = null;
        for (String line : raw.split("\n")) {
            if (line.startsWith("data:")) lastData = line.substring(5).trim();
        }
        if (lastData == null) throw new DraftException("Slack's draft service gave an answer that could not be read.");
        return MAPPER.readTree(lastData);
    }

    private static void failOnRpcError(JsonNode body) {
        if (body.has("error")) throw new DraftException(explain(body.path("error").path("message").asText("")));
    }

    private static String explain(String slackText) {
        if (slackText.contains("draft_already_exists")) {
            return "A draft already exists for this conversation. Ask the user to send or delete the existing "
                    + "draft in Slack (Drafts & Sent) first; Slack allows one draft per channel.";
        }
        if (slackText.contains("channel_not_found")) {
            return "Slack could not find that channel, or the user cannot access it. Check the channel id.";
        }
        if (slackText.contains("not_in_channel")) {
            return "The user is not a member of that channel, so a draft cannot be created there.";
        }
        if (slackText.contains("not enabled for Slack MCP")) {
            return "Drafts need the Slack MCP server turned on for the gateway's Slack app (Agents settings of "
                    + "the app). Ask the gateway administrator. " + slackText;
        }
        if (slackText.contains("missing_scope") || slackText.contains("invalid_auth")
                || slackText.contains("token_expired")) {
            return "Your Slack connection cannot create drafts yet. Disconnect and reconnect Slack on the gateway "
                    + "dashboard (this also needs the gateway writer role).";
        }
        return slackText.isBlank() ? "Slack could not create the draft." : "Slack could not create the draft: " + slackText;
    }

    public static class DraftException extends RuntimeException {
        public DraftException(String message) { super(message); }
    }
}
