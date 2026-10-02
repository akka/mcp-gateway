package io.akka.mcp.gateway.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the write allowlist. Only Workspace Gmail, Workspace Calendar, Workspace Docs and Slack may
 * execute write tools; every other connector — including Workspace Drive and the deprecated
 * standalone Google connectors — is read-only through the gateway.
 *
 * {@code allowsWrites()} is a constant per client, so the collaborators are irrelevant here and
 * passed as null rather than mocked.
 */
public class RemoteMcpClientWriteAllowlistTest {

    private static final String URL = "https://mcp.example.com";
    private static final String APP_ID = "app-id";

    private static List<RemoteMcpClient> writeAllowed() {
        return List.of(
                new WorkspaceGmailMcpClient(null, URL, APP_ID),
                new WorkspaceCalendarMcpClient(null, URL, APP_ID),
                new WorkspaceDocsMcpClient(null, URL, APP_ID),
                new SlackMcpClient(null, null, URL, APP_ID));
    }

    private static List<RemoteMcpClient> readOnly() {
        return List.of(
                new WorkspaceDriveMcpClient(null, URL, APP_ID),
                new ZohoMcpClient(null, URL, APP_ID),
                new SalesforceMcpClient(null, URL, APP_ID),
                new AkkaSalesforceMcpClient(null, null, URL, APP_ID),
                new HubspotMcpClient(null, URL, APP_ID),
                new ReoMcpClient(null, URL, APP_ID),
                new GroundcoverMcpClient(null, URL, APP_ID),
                new OktaMcpClient(URL, null, APP_ID),
                // Deprecated standalone Google connectors: left read-only deliberately.
                new GoogleDriveMcpClient(null, URL, APP_ID),
                new GmailMcpClient(null, URL, APP_ID),
                new GoogleCalendarMcpClient(null, URL, APP_ID));
    }

    @Test
    public void writeAllowedConnectors_permitWrites() {
        for (var client : writeAllowed()) {
            assertThat(client.allowsWrites()).as(client.getMcpId()).isTrue();
        }
    }

    @Test
    public void everyOtherConnector_isReadOnly() {
        for (var client : readOnly()) {
            assertThat(client.allowsWrites()).as(client.getMcpId()).isFalse();
        }
    }

    /** A connector added later inherits read-only, so forgetting to decide fails closed. */
    @Test
    public void interfaceDefault_isReadOnly() {
        var newConnector = new RemoteMcpClient() {
            @Override public String getMcpId() { return "new-connector"; }
            @Override public String getMcpName() { return "New Connector"; }
            @Override public String getRequiredOktaAppId() { return ""; }
            @Override public boolean isConnected(String userId) { return true; }
            @Override public boolean canHandle(String toolName) { return true; }
            @Override public List<ToolEntry> listTools(String userId) { return List.of(); }
            @Override public ToolCallResult callTool(String userId, String toolName, java.util.Map<String, Object> arguments) {
                return new ToolCallResult("", false);
            }
            @Override public HowToContent howTo(String dashboardUrl) { return new HowToContent("", "", ""); }
        };

        assertThat(newConnector.allowsWrites()).isFalse();
    }
}
