package io.akka.mcp.slack.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for Slack's hosted MCP server. It answers initialize and notifications itself and
 * lets a test choose the reply to the draft tool call, as plain JSON or as an event stream.
 */
public final class FakeHostedSlackMcp implements AutoCloseable {

    public record Request(String authorization, String sessionId, JsonNode body) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String SESSION_ID = "session-1";
    public static final String DRAFT_CREATED = "{\"channel_link\":\"https://app.slack.com/client/T1/C123\"}";

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile String toolCallReply = toolResult(DRAFT_CREATED, false);
    private volatile boolean asEventStream = false;
    private volatile int status = 200;

    private FakeHostedSlackMcp(HttpServer server) {
        this.server = server;
    }

    public static FakeHostedSlackMcp start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var fake = new FakeHostedSlackMcp(server);
            server.createContext("/", exchange -> {
                try (exchange) {
                    var body = MAPPER.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    fake.requests.add(new Request(
                            exchange.getRequestHeaders().getFirst("Authorization"),
                            exchange.getRequestHeaders().getFirst("Mcp-Session-Id"),
                            body));
                    String reply = switch (body.path("method").asText()) {
                        case "initialize" -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\","
                                + "\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"Slack MCP\",\"version\":\"1.0.0\"}}}";
                        case "tools/call" -> fake.toolCallReply;
                        default -> "";
                    };
                    if (fake.asEventStream && !reply.isEmpty()) {
                        reply = "event: message\ndata: " + reply + "\n\n";
                        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    } else {
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                    }
                    exchange.getResponseHeaders().add("Mcp-Session-Id", SESSION_ID);
                    var bytes = reply.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(fake.status, bytes.length == 0 ? -1 : bytes.length);
                    if (bytes.length > 0) exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the fake hosted Slack MCP server", e);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    public static String toolResult(String text, boolean isError) {
        try {
            return MAPPER.writeValueAsString(java.util.Map.of("jsonrpc", "2.0", "id", 2, "result",
                    java.util.Map.of("content", List.of(java.util.Map.of("type", "text", "text", text)), "isError", isError)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public FakeHostedSlackMcp replyingToTheToolCallWith(String json) {
        this.toolCallReply = json;
        return this;
    }

    public FakeHostedSlackMcp answeringAsAnEventStream() {
        this.asEventStream = true;
        return this;
    }

    public FakeHostedSlackMcp answeringWithStatus(int status) {
        this.status = status;
        return this;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public Request toolCall() {
        return requests.stream().filter(r -> "tools/call".equals(r.body().path("method").asText())).findFirst()
                .orElseThrow(() -> new AssertionError("the hosted server was never asked to call a tool"));
    }

    public void reset() {
        requests.clear();
        toolCallReply = toolResult(DRAFT_CREATED, false);
        asEventStream = false;
        status = 200;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
