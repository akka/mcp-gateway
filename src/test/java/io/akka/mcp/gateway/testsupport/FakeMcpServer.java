package io.akka.mcp.gateway.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A stand-in for a downstream MCP server, speaking just enough JSON-RPC over HTTP for the gateway's
 * clients: it advertises a fixed set of tools with the same {@code annotations} shape the real
 * servers use, and records the tool calls it receives. Tests assert on what reached it, so they do
 * not depend on any error wording from a failed connection.
 */
public final class FakeMcpServer implements AutoCloseable {

    public record AdvertisedTool(String name, boolean readOnly) {}

    public record ReceivedCall(String tool, String authorization) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final List<ReceivedCall> calls = new CopyOnWriteArrayList<>();
    private volatile List<AdvertisedTool> tools = List.of();

    private FakeMcpServer(HttpServer server) {
        this.server = server;
    }

    public static FakeMcpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var fake = new FakeMcpServer(server);
            server.createContext("/mcp", fake::handle);
            server.setExecutor(Executors.newCachedThreadPool());
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

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
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
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
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
                case "tools/list" -> response.set("result", toolsResult());
                case "tools/call" -> response.set("result", callResult(request.path("params"), exchange));
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

    private static JsonNode initializeResult() {
        var result = MAPPER.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.putObject("capabilities").putObject("tools");
        result.putObject("serverInfo").put("name", "fake-mcp-server").put("version", "1");
        return result;
    }

    private JsonNode toolsResult() {
        var result = MAPPER.createObjectNode();
        var array = result.putArray("tools");
        for (var tool : tools) {
            ObjectNode node = array.addObject();
            node.put("name", tool.name());
            node.put("description", "fake tool");
            node.putObject("inputSchema").put("type", "object").putObject("properties");
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
        calls.add(new ReceivedCall(tool, exchange.getRequestHeaders().getFirst("Authorization")));
        var result = MAPPER.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", "handled " + tool);
        result.put("isError", false);
        return result;
    }
}
