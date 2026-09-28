package io.akka.mcp.gateway.application;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpClientProvider;
import com.typesafe.config.Config;

import java.util.List;

/**
 * The downstream MCP connectors the gateway fronts, excluding the built-in
 * how-to client.
 */
public final class McpClients {

    private McpClients() {
    }

    public static List<RemoteMcpClient> serviceClients(
            ComponentClient componentClient, HttpClientProvider httpClientProvider, Config config) {
        return List.<RemoteMcpClient>of(
                new ZohoMcpClient(componentClient, config.getString("zoho.mcp-url"),
                        config.getString("zoho.okta-app-id")),
                new GoogleDriveMcpClient(componentClient, config.getString("google-drive.mcp-url"),
                        config.getString("google-drive.okta-app-id")),
                new WorkspaceDriveMcpClient(componentClient, config.getString("google-workspace.drive.mcp-url"),
                        config.getString("google-workspace.okta-app-id")),
                new WorkspaceDocsMcpClient(componentClient, config.getString("google-workspace.docs.mcp-url"),
                        config.getString("google-workspace.okta-app-id")),
                new WorkspaceGmailMcpClient(componentClient, config.getString("google-workspace.gmail.mcp-url"),
                        config.getString("google-workspace.okta-app-id")),
                new WorkspaceCalendarMcpClient(componentClient, config.getString("google-workspace.calendar.mcp-url"),
                        config.getString("google-workspace.okta-app-id")),
                new SalesforceMcpClient(componentClient, config.getString("salesforce.mcp-url"),
                        config.getString("salesforce.okta-app-id")),
                new AkkaSalesforceMcpClient(componentClient, httpClientProvider,
                        config.getString("akka-salesforce.mcp-url"), config.getString("akka-salesforce.okta-app-id")),
                new ReoMcpClient(componentClient, config.getString("reo.mcp-url"), config.getString("reo.okta-app-id")),
                new GroundcoverMcpClient(componentClient, config.getString("groundcover.mcp-url"),
                        config.getString("groundcover.okta-app-id")),
                new SlackMcpClient(componentClient, httpClientProvider, config.getString("slack.mcp-url"),
                        config.getString("slack.okta-app-id")),
                new GmailMcpClient(componentClient, config.getString("gmail.mcp-url"),
                        config.getString("gmail.okta-app-id")),
                new GoogleCalendarMcpClient(componentClient, config.getString("google-calendar.mcp-url"),
                        config.getString("google-calendar.okta-app-id")),
                new HubspotMcpClient(componentClient, config.getString("hubspot.mcp-url"),
                        config.getString("hubspot.okta-app-id")),
                new OktaMcpClient(config.getString("okta-admin.mcp-url"), httpClientProvider,
                        config.getString("okta-admin.okta-app-id")),
                new SeamlessMcpClient(componentClient, config.getString("seamless.mcp-url"),
                        config.getString("seamless.okta-app-id")));
    }
}
