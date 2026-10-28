package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.mcp.gateway.application.GoogleWorkspaceConnectionEntity;
import io.akka.mcp.gateway.application.WorkspaceSheetsMcpClient;
import io.akka.mcp.gateway.testsupport.FakeMcpServer;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.AdvertisedTool;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sheets write tools go through the same write gate as every other connector: a fake Sheets MCP
 * advertises one read and one write tool, and each test checks whether a call reaches it.
 */
public class GoogleWorkspaceSheetsWriteGateIntegrationTest extends TestKitSupport {

    private static final String WRITER_GROUP = "mcp-gateway-writer";
    private static final String READER_GROUP = "mcp-gateway-reader";
    private static final String USER_EMAIL = "sheets-writer@lightbend.com";
    private static final String READ_TOOL = "Workspace_GoogleSheets_get_values";
    private static final String WRITE_TOOL = "Workspace_GoogleSheets_update_values";

    private static final FakeMcpServer SHEETS = FakeMcpServer.start().advertising(
            new AdvertisedTool("get_values", true),
            new AdvertisedTool("update_values", false));

    @AfterAll
    public static void stopFakeSheets() {
        SHEETS.close();
    }

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                google-workspace.client-id = "test-client-id"
                google-workspace.drive.mcp-url = ""
                google-workspace.docs.mcp-url = ""
                google-workspace.sheets.mcp-url = "%s"
                google-workspace.gmail.mcp-url = ""
                google-workspace.calendar.mcp-url = ""
                okta.groups.reader = "%s"
                okta.groups.writer = "%s"
                """.formatted(SHEETS.url() + "/mcp", READER_GROUP, WRITER_GROUP));
    }

    /** Workspace connected with the spreadsheets scope, and the registry warmed from the live tool list. */
    @BeforeEach
    public void connectWorkspace() throws Exception {
        SHEETS.forgetCalls();
        componentClient.forKeyValueEntity(USER_EMAIL)
                .method(GoogleWorkspaceConnectionEntity::initiatePkceOAuth)
                .invoke(new GoogleWorkspaceConnectionEntity.InitiateCommand(
                        "state-1", "verifier-1", "test-client-id", "https://oauth2.googleapis.com/token"));
        componentClient.forKeyValueEntity(USER_EMAIL)
                .method(GoogleWorkspaceConnectionEntity::storeToken)
                .invoke(new GoogleWorkspaceConnectionEntity.StoreTokenCommand(
                        "workspace-access", "workspace-refresh", Instant.now().plusSeconds(3600), "state-1"));
        componentClient.forKeyValueEntity(USER_EMAIL)
                .method(GoogleWorkspaceConnectionEntity::recordGrantedScopes)
                .invoke(new GoogleWorkspaceConnectionEntity.RecordGrantedScopesCommand(
                        Set.of(WorkspaceSheetsMcpClient.REQUIRED_SCOPE)));
        rpc(List.of(READER_GROUP, WRITER_GROUP), Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"));
    }

    private JsonNode rpc(List<String> groups, Map<String, Object> request) throws Exception {
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + GatewayFixtures.mcpToken(componentClient, USER_EMAIL, groups))
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();
        return JsonSupport.getObjectMapper().readTree(response.body());
    }

    private JsonNode callTool(String toolName, List<String> groups) throws Exception {
        return rpc(groups, Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", Map.of())));
    }

    private static boolean succeeded(JsonNode response) {
        return response.has("result") && !response.path("result").path("isError").asBoolean(true);
    }

    @Test
    public void writeTool_writesNotEnabledOnSheets_isRefusedForAWriter_andNeverReachesSheets() throws Exception {
        GatewayFixtures.enableWritesOn(componentClient, List.of());

        var response = callTool(WRITE_TOOL, List.of(READER_GROUP, WRITER_GROUP));

        assertThat(response.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(response.path("result").path("content").get(0).path("text").asText())
                .contains("Google Workspace — Sheets is connected read-only through the gateway");
        assertThat(SHEETS.callsTo("update_values")).isEmpty();
    }

    @Test
    public void writeTool_writesEnabledOnSheets_isRefusedForAReader_andNeverReachesSheets() throws Exception {
        GatewayFixtures.enableWritesOn(componentClient, List.of(WorkspaceSheetsMcpClient.MCP_ID));

        var response = callTool(WRITE_TOOL, List.of(READER_GROUP));

        assertThat(response.path("error").path("message").asText()).contains("access not permitted");
        assertThat(response.has("result")).isFalse();
        assertThat(SHEETS.callsTo("update_values")).isEmpty();
    }

    @Test
    public void writeTool_writesEnabledOnSheets_reachesSheetsForAWriter_asTheUser() throws Exception {
        GatewayFixtures.enableWritesOn(componentClient, List.of(WorkspaceSheetsMcpClient.MCP_ID));

        var response = callTool(WRITE_TOOL, List.of(READER_GROUP, WRITER_GROUP));

        assertThat(succeeded(response)).isTrue();
        assertThat(SHEETS.callsTo("update_values")).singleElement()
                .satisfies(call -> assertThat(call.authorization()).isEqualTo("Bearer workspace-access"));
    }

    @Test
    public void readTool_writesNotEnabledOnSheets_reachesSheetsForAReader() throws Exception {
        GatewayFixtures.enableWritesOn(componentClient, List.of());

        var response = callTool(READ_TOOL, List.of(READER_GROUP));

        assertThat(succeeded(response)).isTrue();
        assertThat(SHEETS.callsTo("get_values")).hasSize(1);
    }
}
