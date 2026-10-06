package io.akka.mcp.slack.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Thin wrapper around the Slack Web API. Stateless — caller supplies the user token per call.
 * All methods use the token's own permissions so users can only access what they can normally see.
 */
public class SlackApiClient {

    private static final String DEFAULT_BASE = "https://slack.com/api/";
    private static final String MISSING_SCOPE = "missing_scope";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String token;
    private final String base;

    public SlackApiClient(String token) {
        this(token, DEFAULT_BASE);
    }

    SlackApiClient(String token, String base) {
        this.token = token;
        this.base = base;
    }

    public JsonNode listChannels(String cursor, int limit) throws Exception {
        String url = base + "conversations.list?types=public_channel,private_channel,mpim,im"
                + "&exclude_archived=true"
                + "&limit=" + Math.min(limit, 200)
                + (cursor != null && !cursor.isBlank() ? "&cursor=" + encode(cursor) : "");
        return call(url);
    }

    public JsonNode channelHistory(String channelId, String oldest, String latest, int limit) throws Exception {
        String url = base + "conversations.history?channel=" + encode(channelId)
                + "&limit=" + Math.min(limit, 200)
                + (oldest != null && !oldest.isBlank() ? "&oldest=" + encode(oldest) : "")
                + (latest != null && !latest.isBlank() ? "&latest=" + encode(latest) : "");
        return call(url);
    }

    public JsonNode threadReplies(String channelId, String threadTs, int limit) throws Exception {
        String url = base + "conversations.replies?channel=" + encode(channelId)
                + "&ts=" + encode(threadTs)
                + "&limit=" + Math.min(limit, 200);
        return call(url);
    }

    public JsonNode fileInfo(String fileId) throws Exception {
        return call(base + "files.info?file=" + encode(fileId));
    }

    public JsonNode userInfo(String userId) throws Exception {
        return call(base + "users.info?user=" + encode(userId));
    }

    public JsonNode searchMessages(String query, int page, int count) throws Exception {
        String url = base + "search.messages?query=" + encode(query)
                + "&count=" + Math.min(count, 100)
                + "&page=" + Math.max(page, 1);
        return call(url);
    }

    /**
     * Post a message to a channel, DM, or thread. {@code channel} must be a Slack id
     * ({@code C…}/{@code D…}/{@code G…}); {@code @name}/{@code #name} aren't accepted by the API.
     * Pass {@code threadTs} to reply in a thread; omit for a new top-level message.
     *
     * Requires the user token to carry the {@code chat:write} scope. The gateway only requests it
     * for writers, so {@code missing_scope} means the user connected before gaining write access.
     */
    public JsonNode postMessage(String channel, String text, String threadTs) throws Exception {
        var body = MAPPER.createObjectNode();
        body.put("channel", channel);
        body.put("text", text);
        if (threadTs != null && !threadTs.isBlank()) body.put("thread_ts", threadTs);
        try {
            return callPost(base + "chat.postMessage", MAPPER.writeValueAsString(body));
        } catch (SlackApiException e) {
            if (MISSING_SCOPE.equals(e.slackError())) {
                throw new SlackApiException(MISSING_SCOPE
                        + ": your Slack connection cannot post messages yet. Disconnect and reconnect Slack on the "
                        + "gateway dashboard to grant posting (this also needs the gateway writer role).");
            }
            throw e;
        }
    }

    private JsonNode call(String url) throws Exception {
        var resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        var json = MAPPER.readTree(resp.body());
        if (!json.path("ok").asBoolean()) {
            String error = json.path("error").asText("unknown_error");
            throw new SlackApiException(error);
        }
        return json;
    }

    /**
     * POST a JSON body to a Slack Web API method. Same success semantics as {@link #call(String)}:
     * Slack returns 200 with {@code ok:false} on logical errors, so we must inspect the body
     * rather than relying on the HTTP status.
     */
    private JsonNode callPost(String url, String jsonBody) throws Exception {
        var resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        var json = MAPPER.readTree(resp.body());
        if (!json.path("ok").asBoolean()) {
            String error = json.path("error").asText("unknown_error");
            throw new SlackApiException(error);
        }
        return json;
    }

    private static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    public static class SlackApiException extends RuntimeException {
        private final String slackError;

        public SlackApiException(String slackError) {
            super("Slack API error: " + slackError);
            this.slackError = slackError;
        }

        String slackError() { return slackError; }
    }
}
