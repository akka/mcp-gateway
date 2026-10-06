package io.akka.mcp.slack.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SlackApiClientTest {

    private record Received(String method, String path, String authorization, String contentType, JsonNode body) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer slack;
    private final AtomicReference<Received> received = new AtomicReference<>();
    private volatile String slackReply = "{\"ok\":true,\"ts\":\"1700000000.000100\"}";

    @BeforeEach
    public void startFakeSlack() throws Exception {
        slack = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slack.createContext("/", exchange -> {
            var raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.set(new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    raw.isEmpty() ? null : MAPPER.readTree(raw)));
            var reply = slackReply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        slack.start();
    }

    @AfterEach
    public void stopFakeSlack() {
        slack.stop(0);
    }

    private SlackApiClient client() {
        return new SlackApiClient("xoxp-user-token", "http://127.0.0.1:" + slack.getAddress().getPort() + "/");
    }

    @Test
    public void postMessage_postsJsonToChatPostMessageAsTheUser() throws Exception {
        var reply = client().postMessage("C12345", "hello team", "1699999999.000200");

        var request = received.get();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/chat.postMessage");
        assertThat(request.authorization()).isEqualTo("Bearer xoxp-user-token");
        assertThat(request.contentType()).startsWith("application/json");
        assertThat(request.body().path("channel").asText()).isEqualTo("C12345");
        assertThat(request.body().path("text").asText()).isEqualTo("hello team");
        assertThat(request.body().path("thread_ts").asText()).isEqualTo("1699999999.000200");
        assertThat(reply.path("ts").asText()).isEqualTo("1700000000.000100");
    }

    @Test
    public void postMessage_withoutAThread_postsATopLevelMessage() throws Exception {
        client().postMessage("C12345", "hello team", null);
        assertThat(received.get().body().has("thread_ts")).isFalse();

        client().postMessage("C12345", "hello team", "  ");
        assertThat(received.get().body().has("thread_ts")).isFalse();
    }

    @Test
    public void postMessage_whenTheTokenLacksChatWrite_tellsTheUserHowToFixIt() {
        slackReply = "{\"ok\":false,\"error\":\"missing_scope\"}";

        assertThatThrownBy(() -> client().postMessage("C12345", "hi", null))
                .isInstanceOf(SlackApiClient.SlackApiException.class)
                .hasMessageContaining("missing_scope")
                .hasMessageContaining("writer role")
                .hasMessageContaining("reconnect Slack");
    }

    @Test
    public void postMessage_passesOtherSlackErrorsThroughUnchanged() {
        slackReply = "{\"ok\":false,\"error\":\"channel_not_found\"}";

        assertThatThrownBy(() -> client().postMessage("C0000", "hi", null))
                .isInstanceOf(SlackApiClient.SlackApiException.class)
                .hasMessage("Slack API error: channel_not_found");
    }

    @Test
    public void reads_stillUseGetAgainstTheSameBase() throws Exception {
        client().userInfo("U123");

        var request = received.get();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo("/users.info");
    }
}
