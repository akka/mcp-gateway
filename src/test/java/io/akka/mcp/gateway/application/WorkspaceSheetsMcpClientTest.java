package io.akka.mcp.gateway.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class WorkspaceSheetsMcpClientTest {

    private static WorkspaceSheetsMcpClient client() {
        return new WorkspaceSheetsMcpClient(null, "https://sheetsmcp.googleapis.com/mcp/v1", "0oaGoogleAppIdUnderTest");
    }

    @Test
    public void identifiesItselfAsWorkspaceSheets() {
        assertThat(client().getMcpId()).isEqualTo("google-workspace-sheets");
        assertThat(client().getMcpName()).isEqualTo("Google Workspace — Sheets");
        assertThat(client().getRequiredOktaAppId()).isEqualTo("0oaGoogleAppIdUnderTest");
    }

    @Test
    public void canHandle_onlySheetsPrefixedTools() {
        assertThat(client().canHandle("Workspace_GoogleSheets_get_values")).isTrue();
        assertThat(client().canHandle("Workspace_GoogleDocs_read_doc")).isFalse();
        assertThat(client().canHandle(null)).isFalse();
    }

    @Test
    public void isConnected_notConfigured_isFalse() {
        var unconfigured = new WorkspaceSheetsMcpClient(null, "", "0oaGoogleAppIdUnderTest");
        assertThat(unconfigured.isConnected("user@example.com")).isFalse();
    }

    @Test
    public void howTo_tellsPreSheetsConnectionsToReconnect() {
        var howTo = client().howTo("https://gateway.example.com");
        assertThat(howTo.markdownBody()).contains("https://gateway.example.com").contains("disconnect and connect it");
    }
}
