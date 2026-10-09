package io.akka.mcp.slack.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Saves a draft through Slack's hosted MCP server, because the Slack Web API cannot create drafts.
 * One instance serves one draft: its deadline starts when it is created.
 */
public class SlackDraftClient {

    static final String HOSTED_DRAFT_TOOL = "slack_send_message_draft";

    private static final Logger log = LoggerFactory.getLogger(SlackDraftClient.class);
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEADLINE = Duration.ofSeconds(25);
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int FIRST_ERROR_STATUS = 400;

    private final String token;
    private final String hostedMcpUrl;
    private final long deadlineNanos = System.nanoTime() + DEADLINE.toNanos();

    public SlackDraftClient(String token, String hostedMcpUrl) {
        this.token = token;
        this.hostedMcpUrl = hostedMcpUrl;
    }

    public String createDraft(String channelId, String message, String threadTs) throws Exception {
        var arguments = MAPPER.createObjectNode();
        arguments.put("channel_id", channelId);
        arguments.put("message", message);
        if (threadTs != null && !threadTs.isBlank()) arguments.put("thread_ts", threadTs);

        var initialize = send(null, 1, "initialize", MAPPER.valueToTree(Map.of(
                "protocolVersion", "2025-03-26",
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "akka-mcp-gateway", "version", "1.0.0"))));
        failOnRpcError(initialize.body());

        send(initialize.sessionId(), null, "notifications/initialized", null);

        var call = send(initialize.sessionId(), 2, "tools/call", MAPPER.valueToTree(Map.of(
                "name", HOSTED_DRAFT_TOOL,
                "arguments", arguments)));
        failOnRpcError(call.body());

        JsonNode result = call.body().path("result");
        if (!result.path("content").isArray() || result.path("content").isEmpty()) {
            throw new DraftException("Slack gave no answer, so it is not known whether the draft was saved. "
                    + "Ask the user to check Drafts & Sent in Slack before trying again.");
        }
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

        var remaining = Duration.ofNanos(deadlineNanos - System.nanoTime());
        if (remaining.isNegative() || remaining.isZero()) throw new DraftException(unknownOutcome());

        var builder = HttpRequest.newBuilder()
                .uri(URI.create(hostedMcpUrl))
                .timeout(remaining)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(request), StandardCharsets.UTF_8));
        if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);

        HttpResponse<String> response;
        try {
            response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new DraftException(unknownOutcome());
        }

        failOnHttpStatus(response.statusCode());
        JsonNode body = id == null ? MAPPER.createObjectNode() : parse(response.body(), id);
        return new Reply(response.headers().firstValue("Mcp-Session-Id").orElse(sessionId), body);
    }

    private static String unknownOutcome() {
        return "Slack did not answer in time, so it is not known whether the draft was saved. "
                + "Ask the user to check Drafts & Sent in Slack before trying again.";
    }

    private static void failOnHttpStatus(int status) {
        if (status == 401 || status == 403) {
            throw new DraftException("Slack refused the connection (HTTP " + status
                    + "). Disconnect and reconnect Slack on the gateway dashboard.");
        }
        if (status == TOO_MANY_REQUESTS) {
            throw new DraftException("Slack is rate limiting drafts. Wait a moment and try again.");
        }
        if (status >= FIRST_ERROR_STATUS) {
            throw new DraftException("Slack's draft service is unavailable (HTTP " + status + "). Try again later.");
        }
    }

    /** Plain JSON, or an event stream from which the message answering our request id is picked. */
    private static JsonNode parse(String body, int requestId) throws Exception {
        String raw = body == null ? "" : body.trim();
        if (raw.isEmpty()) return MAPPER.createObjectNode();
        if (raw.startsWith("{")) return MAPPER.readTree(raw);
        for (String event : raw.split("\n\\s*\n")) {
            var data = new StringBuilder();
            for (String line : event.split("\n")) {
                if (line.startsWith("data:")) data.append(line.substring(5).trim());
            }
            if (data.isEmpty()) continue;
            JsonNode message = MAPPER.readTree(data.toString());
            if (message.path("id").asInt(-1) == requestId) return message;
        }
        throw new DraftException("Slack's draft service gave an answer that could not be read.");
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
            log.warn("Slack refused drafts: {}", slackText);
            return "Drafts are not turned on for the gateway's Slack app yet. Ask the gateway administrator.";
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
