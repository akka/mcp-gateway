package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.akka.mcp.gateway.application.GoogleWorkspaceConnectionEntity;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.domain.McpConfig;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Google Sheets behind the Google Workspace connection. The Sheets MCP URL points at a local server that
 * answers every request with 403, as Google does for a token granted before the spreadsheets scope was
 * requested, so a call must come back as the reconnect message rather than a bare status code.
 */
public class GoogleWorkspaceSheetsIntegrationTest extends TestKitSupport {

    private static final String READER_GROUP = "mcp-gateway-reader";
    private static final String READ_TOOL = "Workspace_GoogleSheets_get_values";
    private static final HttpServer REFUSING_SHEETS = startRefusingServer();

    private static HttpServer startRefusingServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(403, -1);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    public static void stopRefusingServer() {
        REFUSING_SHEETS.stop(0);
    }

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                google-workspace.client-id = "test-client-id"
                google-workspace.redirect-uri = "http://localhost/googleworkspace/oauth/callback"
                google-workspace.sheets.mcp-url = "http://127.0.0.1:%d/mcp"
                okta.groups.reader = "%s"
                """.formatted(REFUSING_SHEETS.getAddress().getPort(), READER_GROUP));
    }

    private void connectWorkspace(String email) {
        componentClient.forKeyValueEntity(email)
                .method(GoogleWorkspaceConnectionEntity::initiatePkceOAuth)
                .invoke(new GoogleWorkspaceConnectionEntity.InitiateCommand(
                        "state-1", "verifier-1", "test-client-id", "https://oauth2.googleapis.com/token"));
        componentClient.forKeyValueEntity(email)
                .method(GoogleWorkspaceConnectionEntity::storeToken)
                .invoke(new GoogleWorkspaceConnectionEntity.StoreTokenCommand(
                        "workspace-access", "workspace-refresh", Instant.now().plusSeconds(3600), "state-1"));
    }

    private void seedSheetsReadTool() {
        var tool = new McpConfig.ToolMeta(READ_TOOL, "Read cell values",
                Map.of("type", "object", "properties", Map.of()), true, false);
        componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::register)
                .invoke(new McpConfig("google-workspace-sheets", "Google Workspace — Sheets", List.of(tool)));
    }

    private JsonNode callTool(String token, String toolName) throws Exception {
        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", Map.of()));
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + token)
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();
        return JsonSupport.getObjectMapper().readTree(response.body());
    }

    @Test
    public void sheetsCall_refusedByGoogleWith403_tellsTheUserToReconnect() throws Exception {
        var email = "sheets-old-grant-" + UUID.randomUUID() + "@lightbend.com";
        connectWorkspace(email);
        seedSheetsReadTool();

        var result = callTool(GatewayFixtures.mcpToken(componentClient, email, List.of(READER_GROUP)), READ_TOOL)
                .path("result");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText())
                .contains("disconnect and connect Google Workspace again")
                .doesNotContain("Unexpected status code");
    }

    @Test
    public void workspaceConnect_asksGoogleForTheSpreadsheetsScope() {
        var session = GatewayFixtures.browserSession(componentClient,
                "sheets-connect-" + UUID.randomUUID() + "@lightbend.com", List.of());

        var response = httpClient.GET("/googleworkspace/oauth/connect")
                .addHeader("Cookie", "SESSION=" + session)
                .invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        var location = response.httpResponse().getHeader("Location").orElseThrow().value();
        var scopes = Arrays.stream(URI.create(location).getRawQuery().split("&"))
                .filter(pair -> pair.startsWith("scope="))
                .map(pair -> URLDecoder.decode(pair.substring("scope=".length()), StandardCharsets.UTF_8))
                .flatMap(value -> Arrays.stream(value.split(" ")))
                .toList();
        assertThat(scopes).contains(
                "https://www.googleapis.com/auth/spreadsheets",
                "https://www.googleapis.com/auth/documents",
                "https://www.googleapis.com/auth/drive.file");
    }
}
