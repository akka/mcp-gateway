package io.akka.mcp.gateway.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * A stand-in for a downstream MCP server, speaking just enough JSON-RPC over HTTP for the gateway's
 * clients: it advertises a fixed set of tools with the same {@code annotations} shape the real
 * servers use, and records the tool calls it receives. Tests assert on what reached it, so they do
 * not depend on any error wording from a failed connection.
 */
public final class FakeMcpServer implements AutoCloseable {

    public record AdvertisedTool(String name, boolean readOnly, boolean annotated) {
        public AdvertisedTool(String name, boolean readOnly) {
            this(name, readOnly, true);
        }

        /** A tool that says nothing about whether it reads or writes. */
        public static AdvertisedTool withoutAnnotations(String name) {
            return new AdvertisedTool(name, false, false);
        }
    }

    public record ReceivedCall(String tool, String authorization, String token) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** A tool whose call comes back as a tool-level error, and the text it carries. */
    public static final String ERROR_RESULT_TOOL = "tool_answering_with_an_error";
    public static final String ERROR_RESULT_TEXT = "ERROR-TEXT-FROM-THE-HUB-42";
    /** A tool whose call comes back as a JSON-RPC error, and the message it carries. */
    public static final String PROTOCOL_ERROR_TOOL = "tool_answering_with_a_protocol_error";
    public static final String PROTOCOL_ERROR_TEXT = "PROTOCOL-ERROR-FROM-THE-HUB-43";

    /** What the fake says in the body of every failure it answers with, so a test can check it never reaches a user. */
    public static final String FAILURE_BODY = "BODY-FROM-THE-UPSTREAM-77";
    public static final String HANDLED_PREFIX = "handled ";

    private static final int NO_FORCED_STATUS = 0;
    private static final int TEMPORARY_REDIRECT = 307;
    private static final int UNAUTHORIZED = 401;
    private static final String NO_LOCATION = "";

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<ReceivedCall> calls = new CopyOnWriteArrayList<>();
    private volatile List<AdvertisedTool> tools = List.of();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile Predicate<String> authorizationAccepted = header -> true;
    private final Map<String, List<AdvertisedTool>> toolsByBearer = new ConcurrentHashMap<>();
    private volatile int forcedStatus = NO_FORCED_STATUS;
    private volatile int forcedCallStatus = NO_FORCED_STATUS;
    private volatile String forcedLocation = NO_LOCATION;

    private FakeMcpServer(HttpServer server) {
        this.server = server;
    }

    public static FakeMcpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var fake = new FakeMcpServer(server);
            server.createContext("/mcp", fake::handle);
            server.setExecutor(fake.executor);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the fake MCP server", e);
        }
    }

    public FakeMcpServer advertising(AdvertisedTool... advertised) {
        this.tools = List.of(advertised);
        return this;
    }

    /** Lists these tools, instead of the default ones, to a request carrying this Bearer token. */
    public FakeMcpServer advertisingTo(String bearerToken, AdvertisedTool... advertised) {
        toolsByBearer.put("Bearer " + bearerToken, List.of(advertised));
        return this;
    }

    /** From now on answers every request with this HTTP status and no body. */
    public FakeMcpServer respondingWithStatus(int status) {
        this.forcedLocation = NO_LOCATION;
        this.forcedStatus = status;
        return this;
    }

    /** From now on answers every request by redirecting to this address. */
    public FakeMcpServer respondingWithRedirect(String location) {
        this.forcedLocation = location;
        this.forcedStatus = TEMPORARY_REDIRECT;
        return this;
    }

    /** From now on answers only tool calls with this HTTP status; initialize and tool listings still work. */
    public FakeMcpServer respondingToCallsWithStatus(int status) {
        this.forcedCallStatus = status;
        return this;
    }

    public FakeMcpServer respondingNormally() {
        this.forcedStatus = NO_FORCED_STATUS;
        this.forcedCallStatus = NO_FORCED_STATUS;
        this.forcedLocation = NO_LOCATION;
        return this;
    }

    public FakeMcpServer refusingEveryToken() {
        return acceptingOnlyBearer();
    }

    /** From now on answers 401 unless the request carries one of these Bearer tokens. */
    public FakeMcpServer acceptingOnlyBearer(String... tokens) {
        var accepted = Set.of(tokens);
        this.authorizationAccepted = header -> accepted.stream().anyMatch(token -> ("Bearer " + token).equals(header));
        return this;
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Every HTTP request received so far, whatever it asked for or whether it was accepted. */
    public int requestsReceived() {
        return requests.get();
    }

    public List<ReceivedCall> callsTo(String tool) {
        return calls.stream().filter(c -> c.tool().equals(tool)).toList();
    }

    public void forgetCalls() {
        calls.clear();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.incrementAndGet();
            if (forcedStatus != NO_FORCED_STATUS) {
                exchange.getRequestBody().readAllBytes();
                if (!forcedLocation.equals(NO_LOCATION)) exchange.getResponseHeaders().add("Location", forcedLocation);
                answerWithFailure(exchange, forcedStatus);
                return;
            }
            if (!authorizationAccepted.test(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.getRequestBody().readAllBytes();
                answerWithFailure(exchange, UNAUTHORIZED);
                return;
            }
            var request = MAPPER.readTree(exchange.getRequestBody());
            var method = request.path("method").asText();
            if (request.path("id").isMissingNode()) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            var response = MAPPER.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", request.path("id"));
            switch (method) {
                case "initialize" -> response.set("result", initializeResult());
                case "tools/list" -> response.set("result", toolsResult(exchange));
                case "tools/call" -> {
                    if (forcedCallStatus != NO_FORCED_STATUS) {
                        answerWithFailure(exchange, forcedCallStatus);
                        return;
                    }
                    if (PROTOCOL_ERROR_TOOL.equals(request.path("params").path("name").asText())) {
                        var error = response.putObject("error");
                        error.put("code", -32602);
                        error.put("message", PROTOCOL_ERROR_TEXT);
                    } else {
                        response.set("result", callResult(request.path("params"), exchange));
                    }
                }
                default -> {
                    var error = response.putObject("error");
                    error.put("code", -32601);
                    error.put("message", "Method not found");
                }
            }
            var bytes = MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static void answerWithFailure(HttpExchange exchange, int status) throws IOException {
        var body = FAILURE_BODY.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static JsonNode initializeResult() {
        var result = MAPPER.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.putObject("capabilities").putObject("tools");
        result.putObject("serverInfo").put("name", "fake-mcp-server").put("version", "1");
        return result;
    }

    private JsonNode toolsResult(HttpExchange exchange) {
        var result = MAPPER.createObjectNode();
        var array = result.putArray("tools");
        var authorization = Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), "");
        var listed = toolsByBearer.getOrDefault(authorization, tools);
        for (var tool : listed) {
            ObjectNode node = array.addObject();
            node.put("name", tool.name());
            node.put("description", "fake tool");
            node.putObject("inputSchema").put("type", "object").putObject("properties");
            if (!tool.annotated()) continue;
            var annotations = node.putObject("annotations");
            annotations.put("readOnlyHint", tool.readOnly());
            if (!tool.readOnly()) {
                annotations.put("destructiveHint", false);
                annotations.put("idempotentHint", false);
            }
        }
        return result;
    }

    private JsonNode callResult(JsonNode params, HttpExchange exchange) {
        var tool = params.path("name").asText();
        calls.add(new ReceivedCall(tool, exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Token")));
        var result = MAPPER.createObjectNode();
        var failing = ERROR_RESULT_TOOL.equals(tool);
        result.putArray("content").addObject().put("type", "text").put("text", failing ? ERROR_RESULT_TEXT : HANDLED_PREFIX + tool);
        result.put("isError", failing);
        return result;
    }
}
