package io.akka.mcp.gateway.application;

import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;

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

    @Test
    public void isScopeRefusal_http403_isRefusal() {
        assertThat(WorkspaceSheetsMcpClient.isScopeRefusal(new RuntimeException("Unexpected status code: 403"))).isTrue();
    }

    @Test
    public void isScopeRefusal_http403WrappedInCause_isRefusal() {
        var wrapped = new CompletionException(new RuntimeException("Unexpected status code: 403"));
        assertThat(WorkspaceSheetsMcpClient.isScopeRefusal(wrapped)).isTrue();
    }

    @Test
    public void isScopeRefusal_googleScopeReasonInMessage_isRefusal() {
        var error = new RuntimeException("Request had insufficient authentication scopes. ACCESS_TOKEN_SCOPE_INSUFFICIENT");
        assertThat(WorkspaceSheetsMcpClient.isScopeRefusal(error)).isTrue();
    }

    @Test
    public void isScopeRefusal_otherFailures_areNotRefusals() {
        assertThat(WorkspaceSheetsMcpClient.isScopeRefusal(new RuntimeException("Unexpected status code: 401"))).isFalse();
        assertThat(WorkspaceSheetsMcpClient.isScopeRefusal(new RuntimeException("Unexpected status code: 500"))).isFalse();
        assertThat(WorkspaceSheetsMcpClient.isScopeRefusal(new HttpTimeoutException("request timed out"))).isFalse();
        assertThat(WorkspaceSheetsMcpClient.isScopeRefusal(new RuntimeException((String) null))).isFalse();
    }

    @Test
    public void reportsMissingScope_googleWordings_areDetected() {
        assertThat(WorkspaceSheetsMcpClient.reportsMissingScope("ACCESS_TOKEN_SCOPE_INSUFFICIENT")).isTrue();
        assertThat(WorkspaceSheetsMcpClient.reportsMissingScope("Request had insufficient authentication scopes.")).isTrue();
        assertThat(WorkspaceSheetsMcpClient.reportsMissingScope("Bearer error=\"insufficient_scope\"")).isTrue();
    }

    @Test
    public void reportsMissingScope_ordinaryToolErrors_areNotRewritten() {
        assertThat(WorkspaceSheetsMcpClient.reportsMissingScope("Requested entity was not found.")).isFalse();
        assertThat(WorkspaceSheetsMcpClient.reportsMissingScope("The caller does not have permission")).isFalse();
        assertThat(WorkspaceSheetsMcpClient.reportsMissingScope(null)).isFalse();
    }
}
