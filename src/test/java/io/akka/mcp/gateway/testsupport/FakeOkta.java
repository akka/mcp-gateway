package io.akka.mcp.gateway.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for the Okta management API. Tests script the answer for a path (an exact path wins
 * over a path prefix) and assert on the requests that arrived, so they never depend on the wording
 * of an error from a failed connection.
 */
public final class FakeOkta implements AutoCloseable {

    public record Reply(int status, String body, Map<String, String> headers) {
        public static Reply of(int status, String body) {
            return new Reply(status, body, Map.of());
        }

        public Reply withHeader(String name, String value) {
            var all = new LinkedHashMap<>(headers);
            all.put(name, value);
            return new Reply(status, body, all);
        }
    }

    public record ReceivedRequest(String pathAndQuery, String authorization) {}

    private final HttpServer server;
    private final Map<String, Deque<Reply>> replies = new LinkedHashMap<>();
    private final List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();

    private FakeOkta(HttpServer server) {
        this.server = server;
    }

    public static FakeOkta start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var fake = new FakeOkta(server);
            server.createContext("/", fake::handle);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the fake Okta", e);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public FakeOkta respond(String path, int status, String body) {
        return respondInOrder(path, Reply.of(status, body));
    }

    /** Serves the replies one after another, repeating the last one once the rest are used up. */
    public synchronized FakeOkta respondInOrder(String path, Reply... inOrder) {
        replies.put(path, new ArrayDeque<>(List.of(inOrder)));
        return this;
    }

    public synchronized FakeOkta reset() {
        replies.clear();
        requests.clear();
        return this;
    }

    public List<ReceivedRequest> requests() {
        return new ArrayList<>(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(new ReceivedRequest(
                URLDecoder.decode(exchange.getRequestURI().toString(), StandardCharsets.UTF_8),
                exchange.getRequestHeaders().getFirst("Authorization")));
        var reply = nextReplyFor(exchange.getRequestURI().getPath());
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private synchronized Reply nextReplyFor(String path) {
        var queue = replies.get(path);
        if (queue == null) {
            queue = replies.entrySet().stream()
                    .filter(e -> path.startsWith(e.getKey()))
                    .max((a, b) -> Integer.compare(a.getKey().length(), b.getKey().length()))
                    .map(Map.Entry::getValue)
                    .orElse(null);
        }
        if (queue == null) return Reply.of(404, "{}");
        return queue.size() > 1 ? queue.poll() : queue.peek();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
