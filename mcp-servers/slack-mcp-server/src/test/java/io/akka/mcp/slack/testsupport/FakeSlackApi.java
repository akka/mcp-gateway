package io.akka.mcp.slack.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for the Slack Web API that records the requests it receives and answers with a canned
 * reply, so tests can assert on exactly what would have been sent to Slack.
 */
public final class FakeSlackApi implements AutoCloseable {

    public record Request(String method, String path, String authorization, String contentType, JsonNode body) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String OK_REPLY = "{\"ok\":true,\"ts\":\"1700000000.000100\"}";
    private static final String INTERNAL_CHANNEL = channel("eng", false, false);

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> repliesByPath = new ConcurrentHashMap<>();
    private volatile String reply = OK_REPLY;

    private FakeSlackApi(HttpServer server) {
        this.server = server;
    }

    public static FakeSlackApi start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var fake = new FakeSlackApi(server);
            fake.reset();
            server.createContext("/", exchange -> {
                try (exchange) {
                    var raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    fake.requests.add(new Request(
                            exchange.getRequestMethod(),
                            exchange.getRequestURI().getPath(),
                            exchange.getRequestHeaders().getFirst("Authorization"),
                            exchange.getRequestHeaders().getFirst("Content-Type"),
                            raw.isEmpty() ? null : MAPPER.readTree(raw)));
                    var path = exchange.getRequestURI().getPath();
                    var bytes = fake.repliesByPath.getOrDefault(path, fake.reply).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the fake Slack API", e);
        }
    }

    /** The base URL to hand to the code under test, with the trailing slash the Slack client expects. */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    /** The reply for every path that has no reply of its own. */
    public FakeSlackApi replyingWith(String json) {
        this.reply = json;
        return this;
    }

    public FakeSlackApi replyingWith(String path, String json) {
        repliesByPath.put(path, json);
        return this;
    }

    /** A {@code conversations.info} reply describing one channel. */
    public static String channel(String name, boolean extShared, boolean pendingExtShared) {
        return "{\"ok\":true,\"channel\":{\"id\":\"C123\",\"name\":\"" + name
                + "\",\"is_ext_shared\":" + extShared + ",\"is_pending_ext_shared\":" + pendingExtShared + "}}";
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public Request onlyRequest() {
        if (requests.size() != 1) throw new AssertionError("expected exactly one request to Slack but saw " + requests.size());
        return requests.get(0);
    }

    /** Back to answering every path with {@link #OK_REPLY}, except that every channel is an ordinary internal one. */
    public void reset() {
        requests.clear();
        repliesByPath.clear();
        repliesByPath.put("/conversations.info", INTERNAL_CHANNEL);
        reply = OK_REPLY;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
