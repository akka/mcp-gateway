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

    private static final String BASE = "https://slack.com/api/";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String token;

    public SlackApiClient(String token) {
        this.token = token;
    }

    public JsonNode listChannels(String cursor, int limit) throws Exception {
        String url = BASE + "conversations.list?types=public_channel,private_channel,mpim,im"
                + "&exclude_archived=true"
                + "&limit=" + Math.min(limit, 200)
                + (cursor != null && !cursor.isBlank() ? "&cursor=" + encode(cursor) : "");
        return call(url);
    }

    public JsonNode channelHistory(String channelId, String oldest, String latest, int limit) throws Exception {
        String url = BASE + "conversations.history?channel=" + encode(channelId)
                + "&limit=" + Math.min(limit, 200)
                + (oldest != null && !oldest.isBlank() ? "&oldest=" + encode(oldest) : "")
                + (latest != null && !latest.isBlank() ? "&latest=" + encode(latest) : "");
        return call(url);
    }

    public JsonNode threadReplies(String channelId, String threadTs, int limit) throws Exception {
        String url = BASE + "conversations.replies?channel=" + encode(channelId)
                + "&ts=" + encode(threadTs)
                + "&limit=" + Math.min(limit, 200);
        return call(url);
    }

    public JsonNode fileInfo(String fileId) throws Exception {
        return call(BASE + "files.info?file=" + encode(fileId));
    }

    public JsonNode userInfo(String userId) throws Exception {
        return call(BASE + "users.info?user=" + encode(userId));
    }

    public JsonNode searchMessages(String query, int page, int count) throws Exception {
        String url = BASE + "search.messages?query=" + encode(query)
                + "&count=" + Math.min(count, 100)
                + "&page=" + Math.max(page, 1);
        return call(url);
    }

    /**
     * Post a message to a channel, DM, or thread. {@code channel} must be a Slack id
     * ({@code C…}/{@code D…}/{@code G…}); {@code @name}/{@code #name} aren't accepted by the API.
     * Pass {@code threadTs} to reply in a thread; omit for a new top-level message.
     *
     * Requires the user token to carry the {@code chat:write} scope. Slack returns
     * {@code missing_scope} otherwise — surface that as-is so the caller knows to reconnect.
     */
    public JsonNode postMessage(String channel, String text, String threadTs) throws Exception {
        var body = MAPPER.createObjectNode();
        body.put("channel", channel);
        body.put("text", text);
        if (threadTs != null && !threadTs.isBlank()) body.put("thread_ts", threadTs);
        return callPost(BASE + "chat.postMessage", MAPPER.writeValueAsString(body));
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
        public SlackApiException(String slackError) {
            super("Slack API error: " + slackError);
        }
    }
}
