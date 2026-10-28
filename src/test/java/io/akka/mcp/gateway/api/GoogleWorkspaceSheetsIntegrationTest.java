package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.akka.mcp.gateway.application.GoogleWorkspaceConnectionEntity;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.domain.GoogleWorkspaceConnection.ScopeGrant;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A local server stands in for Google: {@code /token} grants a pre-Sheets set of scopes, and every
 * MCP request is refused with 403.
 */
public class GoogleWorkspaceSheetsIntegrationTest extends TestKitSupport {

    private static final String READER_GROUP = "mcp-gateway-reader";
    private static final String SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets";
    private static final String READ_TOOL = "Workspace_GoogleSheets_get_values";
    private static final String PRE_SHEETS_SCOPES = "openid email https://www.googleapis.com/auth/documents";
    private static final HttpServer FAKE_GOOGLE = startFakeGoogle();

    private static HttpServer startFakeGoogle() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    byte[] body = ("{\"access_token\":\"workspace-access\",\"refresh_token\":\"workspace-refresh\","
                            + "\"expires_in\":3600,\"scope\":\"" + PRE_SHEETS_SCOPES + "\"}").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
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
    public static void stopFakeGoogle() {
        FAKE_GOOGLE.stop(0);
    }

    private static String fakeGoogleUrl(String path) {
        return "http://127.0.0.1:" + FAKE_GOOGLE.getAddress().getPort() + path;
    }

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                google-workspace.client-id = "test-client-id"
                google-workspace.redirect-uri = "http://localhost/googleworkspace/oauth/callback"
                google-workspace.drive.mcp-url = "%1$s"
                google-workspace.docs.mcp-url = "%1$s"
                google-workspace.sheets.mcp-url = "%1$s"
                google-workspace.gmail.mcp-url = "%1$s"
                google-workspace.calendar.mcp-url = "%1$s"
                okta.groups.reader = "%2$s"
                """.formatted(fakeGoogleUrl("/mcp"), READER_GROUP));
    }

    private void connectWorkspace(String email) {
        componentClient.forKeyValueEntity(email)
                .method(GoogleWorkspaceConnectionEntity::initiatePkceOAuth)
                .invoke(new GoogleWorkspaceConnectionEntity.InitiateCommand(
                        "state-1", "verifier-1", "test-client-id", fakeGoogleUrl("/token")));
        componentClient.forKeyValueEntity(email)
                .method(GoogleWorkspaceConnectionEntity::storeToken)
                .invoke(new GoogleWorkspaceConnectionEntity.StoreTokenCommand(
                        "workspace-access", "workspace-refresh", Instant.now().plusSeconds(3600), "state-1"));
    }

    private void connectWorkspaceWithoutSheetsScope(String email) {
        connectWorkspace(email);
        componentClient.forKeyValueEntity(email)
                .method(GoogleWorkspaceConnectionEntity::recordGrantedScopes)
                .invoke(new GoogleWorkspaceConnectionEntity.RecordGrantedScopesCommand(Set.of("openid", "email")));
    }

    private void seedSheetsReadTool() {
        var tool = new McpConfig.ToolMeta(READ_TOOL, "Read cell values",
                Map.of("type", "object", "properties", Map.of()), true, false);
        componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::register)
                .invoke(new McpConfig("google-workspace-sheets", "Google Workspace — Sheets", List.of(tool)));
    }

    private JsonNode mcp(String token, String method, Map<String, Object> params) throws Exception {
        Map<String, Object> request = Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params);
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + token)
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();
        return JsonSupport.getObjectMapper().readTree(response.body()).path("result");
    }

    private JsonNode callSheetsTool(String email) throws Exception {
        var token = GatewayFixtures.mcpToken(componentClient, email, List.of(READER_GROUP));
        return mcp(token, "tools/call", Map.of("name", READ_TOOL, "arguments", Map.of()));
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
                SHEETS_SCOPE,
                "https://www.googleapis.com/auth/documents",
                "https://www.googleapis.com/auth/drive.file");
    }

    @Test
    public void workspaceCallback_recordsTheScopesGoogleGranted() {
        var email = "sheets-callback-" + UUID.randomUUID() + "@lightbend.com";
        var session = GatewayFixtures.browserSession(componentClient, email, List.of());
        componentClient.forKeyValueEntity(email)
                .method(GoogleWorkspaceConnectionEntity::initiatePkceOAuth)
                .invoke(new GoogleWorkspaceConnectionEntity.InitiateCommand(
                        "callback-state", "verifier", "test-client-id", fakeGoogleUrl("/token")));

        httpClient.GET("/googleworkspace/oauth/callback?code=auth-code&state=callback-state")
                .addHeader("Cookie", "SESSION=" + session)
                .invoke();

        var connection = componentClient.forKeyValueEntity(email)
                .method(GoogleWorkspaceConnectionEntity::getStatus)
                .invoke();
        assertThat(connection.accessToken()).isEqualTo("workspace-access");
        assertThat(connection.grantedScopes())
                .containsExactlyInAnyOrder("openid", "email", "https://www.googleapis.com/auth/documents");
        assertThat(connection.scopeGrant(SHEETS_SCOPE)).isEqualTo(ScopeGrant.MISSING);
    }

    @Test
    public void sheetsCall_grantWithoutSpreadsheetsScope_isReportedNotConnected() throws Exception {
        var email = "sheets-old-grant-" + UUID.randomUUID() + "@lightbend.com";
        connectWorkspaceWithoutSheetsScope(email);
        seedSheetsReadTool();

        var result = callSheetsTool(email);

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText())
                .contains("Google Workspace — Sheets is not connected")
                .contains("howto_connect_google_workspace_sheets");
    }

    @Test
    public void sheetsCall_scopesNeverRecorded_isSentToGoogle() throws Exception {
        var email = "sheets-unknown-grant-" + UUID.randomUUID() + "@lightbend.com";
        connectWorkspace(email);
        seedSheetsReadTool();

        var result = callSheetsTool(email);

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").get(0).path("text").asText())
                .doesNotContain("not connected")
                .contains("403");
    }

    @Test
    public void toolsList_grantWithoutSpreadsheetsScope_stillListsSheetsTools() throws Exception {
        var email = "sheets-list-" + UUID.randomUUID() + "@lightbend.com";
        connectWorkspaceWithoutSheetsScope(email);
        seedSheetsReadTool();
        var token = GatewayFixtures.mcpToken(componentClient, email, List.of(READER_GROUP));

        var tools = mcp(token, "tools/list", Map.of()).path("tools");

        var names = new ArrayList<String>();
        tools.forEach(t -> names.add(t.path("name").asText()));
        assertThat(names).contains(READ_TOOL);
    }
}
