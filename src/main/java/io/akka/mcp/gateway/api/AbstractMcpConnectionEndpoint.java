package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.application.RemoteMcpClient;
import io.akka.mcp.gateway.domain.McpConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

public abstract class AbstractMcpConnectionEndpoint extends AbstractProtectedEndpoint {

    private static final Logger log = LoggerFactory.getLogger(AbstractMcpConnectionEndpoint.class);
    protected static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern RESOURCE_METADATA_PATTERN =
            Pattern.compile("resource_metadata=\"([^\"]+)\"");

    public record ConnectionStatus(boolean connected, Instant tokenExpiresAt) {}

    public record PendingOAuthState(String clientId, String codeVerifier, String tokenEndpoint) {}

    public record TestResult(boolean ok, String message, List<String> tools) {
        public static TestResult ok(String message, List<String> tools) {
            return new TestResult(true, message, tools);
        }
        public static TestResult fail(String message) {
            return new TestResult(false, message, List.of());
        }
    }

    public record TokenResult(String token) {}

    protected AbstractMcpConnectionEndpoint(ComponentClient componentClient, Config config) {
        super(componentClient, config);
    }

    protected abstract ConnectionStatus fetchConnectionStatus(String email);

    @Get("/status")
    public HttpResponse status() {
        if (requireSession() == null) return redirectToLogin();
        return HttpResponses.ok(fetchConnectionStatus(requireSession().email()));
    }

    /**
     * Best-effort: populate the global tool-schema cache right after a successful connect, using the
     * just-connected user's token. Tool schemas are the same for every user, so one connection is
     * enough to keep {@code tools/list}'s cache warm — a later live-fetch failure then degrades to a
     * real tool list instead of nothing, removing the need for a manual refresh. Never throws.
     */
    protected void warmRegistryCache(RemoteMcpClient client, String userId) {
        try {
            var entries = client.listTools(userId);
            if (entries.isEmpty()) {
                log.info("[{}] cache warm: upstream returned 0 tools, leaving cache unchanged", client.getMcpId());
                return;
            }
            var metas = entries.stream().map(RemoteMcpClient.ToolEntry::meta).toList();
            componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                    .method(McpRegistryEntity::register)
                    .invoke(new McpConfig(client.getMcpId(), client.getMcpName(), metas));
            log.info("[{}] cache warm: registered {} tools", client.getMcpId(), metas.size());
        } catch (Exception e) {
            log.warn("[{}] cache warm failed (non-fatal): {}", client.getMcpId(), e.getMessage());
        }
    }

    protected TestResult testMcpClient(RemoteMcpClient client, String userId, String label) {
        try {
            var entries = client.listTools(userId);
            var toolNames = entries.stream()
                    .map(e -> (String) e.toolSpec().get("name"))
                    .toList();
            String msg = "Connected — " + toolNames.size() + " tool(s) available";
            log.info("[{}] Test Connection result for {}: {}", label, userId, msg);
            return TestResult.ok(msg, toolNames);
        } catch (Exception e) {
            log.warn("[{}] Test Connection failed for {}: {}", label, userId, e.getMessage());
            return TestResult.fail("Connection failed: " + e.getMessage());
        }
    }

    protected static TestResult probeMcpServer(String mcpUrl, String bearerToken) {
        try {
            var httpClient = HttpClient.newHttpClient();

            String initBody = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":1," +
                    "\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{}," +
                    "\"clientInfo\":{\"name\":\"mcp-gateway\",\"version\":\"1.0\"}}}";
            var initResp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(mcpUrl))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json, text/event-stream")
                            .header("MCP-Protocol-Version", "2024-11-05")
                            .header("Authorization", "Bearer " + bearerToken)
                            .POST(HttpRequest.BodyPublishers.ofString(initBody))
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (initResp.statusCode() == 401) {
                return TestResult.fail("Token rejected by MCP server (401 Unauthorized)");
            }
            if (initResp.statusCode() != 200) {
                return TestResult.fail("initialize failed HTTP " + initResp.statusCode() + ": " + initResp.body());
            }
            String sessionKey = initResp.headers().firstValue("Mcp-Session-Id").orElse(null);

            String listBody = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":2,\"params\":{}}";
            var reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(mcpUrl))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", "2024-11-05")
                    .header("Authorization", "Bearer " + bearerToken);
            if (sessionKey != null) reqBuilder.header("Mcp-Session-Id", sessionKey);
            var listResp = httpClient.send(
                    reqBuilder.POST(HttpRequest.BodyPublishers.ofString(listBody)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (listResp.statusCode() != 200) {
                return TestResult.fail("tools/list failed HTTP " + listResp.statusCode() + ": " + listResp.body());
            }
            log.info("[MCP probe] tools/list response content-type={} body={}", listResp.headers().firstValue("content-type").orElse("?"), listResp.body());
            String responseBody = listResp.body();
            if (responseBody.startsWith("data:")) {
                responseBody = responseBody.lines()
                        .filter(l -> l.startsWith("data:"))
                        .map(l -> l.substring(5).strip())
                        .findFirst().orElse(responseBody);
            }
            var json = MAPPER.readTree(responseBody);
            var toolsNode = json.path("result").path("tools");
            var toolNames = new ArrayList<String>();
            if (toolsNode.isArray()) {
                for (var t : toolsNode) {
                    String name = t.path("name").asText("");
                    if (!name.isBlank()) toolNames.add(name);
                }
            }
            String msg = toolNames.isEmpty() ? "Connected" : "Connected — " + toolNames.size() + " tool(s) available";
            return TestResult.ok(msg, toolNames);
        } catch (Exception e) {
            return TestResult.fail("Connection error: " + e.getMessage());
        }
    }

    protected static String extractResourceMetadata(String wwwAuthenticate) {
        var matcher = RESOURCE_METADATA_PATTERN.matcher(wwwAuthenticate);
        return matcher.find() ? matcher.group(1) : null;
    }

    protected static String buildAuthServerMetadataUrl(String issuerUrl) {
        String base = issuerUrl.endsWith("/") ? issuerUrl.substring(0, issuerUrl.length() - 1) : issuerUrl;
        try {
            var uri = URI.create(base);
            String origin = uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
            String path = uri.getPath();
            if (path == null || path.isEmpty() || path.equals("/")) {
                return origin + "/.well-known/oauth-authorization-server";
            } else {
                return origin + "/.well-known/oauth-authorization-server" + path;
            }
        } catch (Exception e) {
            return base + "/.well-known/oauth-authorization-server";
        }
    }

    protected static String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    protected static String generateCodeChallenge(String codeVerifier) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    protected static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
