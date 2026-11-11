package io.akka.mcp.gateway.application;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpClientProvider;
import com.typesafe.config.Config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** The downstream MCP connectors the gateway fronts, excluding the built-in how-to client. */
public final class McpClients {

    private static final Logger log = LoggerFactory.getLogger(McpClients.class);
    private static final Set<String> REPORTED_COLLISIONS = ConcurrentHashMap.newKeySet();

    private McpClients() {}

    public static List<RemoteMcpClient> serviceClients(
            ComponentClient componentClient, HttpClientProvider httpClientProvider, Config config) {
        var builtIns = builtInClients(componentClient, httpClientProvider, config);
        var clients = new ArrayList<>(builtIns);
        clients.addAll(tokenClients(componentClient, httpClientProvider, config, builtIns));
        return List.copyOf(clients);
    }

    private static List<RemoteMcpClient> builtInClients(
            ComponentClient componentClient, HttpClientProvider httpClientProvider, Config config) {
        var clients = new ArrayList<RemoteMcpClient>();
        clients.add(new ZohoMcpClient(componentClient, config.getString("zoho.mcp-url"), config.getString("zoho.okta-app-id")));
        clients.add(new GoogleDriveMcpClient(componentClient, config.getString("google-drive.mcp-url"),
                config.getString("google-drive.okta-app-id")));
        clients.addAll(workspaceClients(componentClient, config));
        clients.addAll(List.of(
                new SalesforceMcpClient(componentClient, config.getString("salesforce.mcp-url"), config.getString("salesforce.okta-app-id")),
                new AkkaSalesforceMcpClient(componentClient, httpClientProvider, config.getString("akka-salesforce.mcp-url"),
                        config.getString("akka-salesforce.okta-app-id")),
                new ReoMcpClient(componentClient, config.getString("reo.mcp-url"), config.getString("reo.okta-app-id")),
                new GroundcoverMcpClient(componentClient, config.getString("groundcover.mcp-url"), config.getString("groundcover.okta-app-id")),
                new SlackMcpClient(componentClient, httpClientProvider, config.getString("slack.mcp-url"), config.getString("slack.okta-app-id")),
                new GmailMcpClient(componentClient, config.getString("gmail.mcp-url"), config.getString("gmail.okta-app-id")),
                new GoogleCalendarMcpClient(componentClient, config.getString("google-calendar.mcp-url"), config.getString("google-calendar.okta-app-id")),
                new HubspotMcpClient(componentClient, config.getString("hubspot.mcp-url"), config.getString("hubspot.okta-app-id")),
                new OktaMcpClient(config.getString("okta-admin.mcp-url"), httpClientProvider, config.getString("okta-admin.okta-app-id")),
                new SeamlessMcpClient(config.getString("seamless.mcp-url"), config.getString("seamless.api-key"), config.getString("seamless.okta-app-id"))
        ));
        return List.copyOf(clients);
    }

    /** The operator's token connectors, leaving out any whose id, name or tool prefix a built-in system already uses. */
    public static List<TokenMcpClient> tokenClients(
            ComponentClient componentClient, HttpClientProvider httpClientProvider, Config config) {
        return tokenClients(componentClient, httpClientProvider, config, builtInClients(componentClient, httpClientProvider, config));
    }

    private static List<TokenMcpClient> tokenClients(
            ComponentClient componentClient, HttpClientProvider httpClientProvider, Config config, List<RemoteMcpClient> builtIns) {
        Predicate<String> takenByBuiltIn = toolName -> builtIns.stream().anyMatch(builtIn -> builtIn.canHandle(toolName));
        return TokenMcpConfig.from(config).stream()
                .filter(entry -> !collidesWithBuiltIn(entry, builtIns))
                .map(entry -> new TokenMcpClient(componentClient, httpClientProvider, entry,
                        new PastedTokenSource(componentClient, entry.id()), takenByBuiltIn))
                .toList();
    }

    private static boolean collidesWithBuiltIn(TokenMcpConfig entry, List<RemoteMcpClient> builtIns) {
        var collides = builtIns.stream().anyMatch(builtIn ->
                builtIn.getMcpId().equals(entry.id()) || builtIn.getMcpName().equalsIgnoreCase(entry.name())
                        || builtIn.canHandle(entry.toolPrefix() + "x"));
        if (collides && REPORTED_COLLISIONS.add(entry.id())) {
            log.error("token-mcps entry {} is skipped: it collides with a built-in system", entry.id());
        }
        return collides;
    }

    /** The Google Workspace products, which all authenticate with the one Google Workspace connection. */
    public static List<RemoteMcpClient> workspaceClients(ComponentClient componentClient, Config config) {
        String oktaAppId = config.getString("google-workspace.okta-app-id");
        return List.of(
                new WorkspaceDriveMcpClient(componentClient, config.getString("google-workspace.drive.mcp-url"), oktaAppId),
                new WorkspaceDocsMcpClient(componentClient, config.getString("google-workspace.docs.mcp-url"), oktaAppId),
                new WorkspaceSheetsMcpClient(componentClient, config.getString("google-workspace.sheets.mcp-url"), oktaAppId),
                new WorkspaceGmailMcpClient(componentClient, config.getString("google-workspace.gmail.mcp-url"), oktaAppId),
                new WorkspaceCalendarMcpClient(componentClient, config.getString("google-workspace.calendar.mcp-url"), oktaAppId));
    }
}

