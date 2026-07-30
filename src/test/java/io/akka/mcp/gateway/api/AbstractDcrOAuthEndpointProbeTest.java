package io.akka.mcp.gateway.api;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the RFC 9728 discovery probe against stub MCP servers that gate auth differently.
 * The Reo case (challenge only on tools/call) is the one that regressed in production.
 */
public class AbstractDcrOAuthEndpointProbeTest {

    private HttpServer server;

    @AfterEach
    public void stop() {
        if (server != null) server.stop(0);
    }

    /** Start a stub MCP server; the handler decides the status + optional WWW-Authenticate per method. */
    private String start(java.util.function.Function<String, Response> router) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String method = body.contains("\"tools/call\"") ? "tools/call"
                    : body.contains("\"tools/list\"") ? "tools/list" : "initialize";
            Response r = router.apply(method);
            if (r.wwwAuthenticate != null) {
                exchange.getResponseHeaders().add("WWW-Authenticate", r.wwwAuthenticate);
            }
            byte[] out = r.body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(r.status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    private record Response(int status, String wwwAuthenticate, String body) {}

    private static final String CHALLENGE =
            "Bearer realm=\"stub\", resource_metadata=\"https://auth.example/.well-known/oauth-protected-resource\"";

    @Test
    public void escalatesToToolsCall_whenInitializeIsOpen() throws Exception {
        // Reo's behaviour: initialize returns 200 open, only tools/call challenges.
        String url = start(method -> method.equals("tools/call")
                ? new Response(401, CHALLENGE, "{\"error\":\"unauthorized\"}")
                : new Response(200, null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));

        var probe = AbstractDcrOAuthEndpoint.discoverResourceMetadata(HttpClient.newHttpClient(), url);

        assertThat(probe.found()).isTrue();
        assertThat(probe.resourceMetadataUrl())
                .isEqualTo("https://auth.example/.well-known/oauth-protected-resource");
    }

    @Test
    public void usesInitializeChallenge_whenServerChallengesThere() throws Exception {
        // Providers that challenge on initialize must still work without the tools/call probe.
        String url = start(method -> new Response(401, CHALLENGE, "{\"error\":\"unauthorized\"}"));

        var probe = AbstractDcrOAuthEndpoint.discoverResourceMetadata(HttpClient.newHttpClient(), url);

        assertThat(probe.found()).isTrue();
        assertThat(probe.resourceMetadataUrl())
                .isEqualTo("https://auth.example/.well-known/oauth-protected-resource");
    }

    @Test
    public void reportsNoChallenge_whenServerNeverChallenges() throws Exception {
        // A fully open server yields no resource_metadata; diagnostics capture both probes.
        String url = start(method -> new Response(200, null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));

        var probe = AbstractDcrOAuthEndpoint.discoverResourceMetadata(HttpClient.newHttpClient(), url);

        assertThat(probe.found()).isFalse();
        assertThat(probe.diagnostics()).contains("initialize → HTTP 200", "tools/call → HTTP 200");
    }
}
