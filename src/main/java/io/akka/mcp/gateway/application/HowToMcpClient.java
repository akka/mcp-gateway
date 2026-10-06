package io.akka.mcp.gateway.application;

import io.akka.mcp.gateway.domain.McpConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HowToMcpClient implements RemoteMcpClient {

    public static final String MCP_ID = "howto";
    public static final String MCP_NAME = "How To Use MCP Gateway";

    private final String dashboardUrl;
    private final String mcpConnectUrl;
    private final String supportEmail;
    private final String supportSlackChannel;
    private final List<RemoteMcpClient> serviceClients;
    private final Map<String, String> connectContentByName; // static per-service connect markdown

    public HowToMcpClient(String baseUrl, String supportEmail, String supportSlackChannel, List<RemoteMcpClient> clients) {
        this.dashboardUrl = baseUrl + "/";
        this.mcpConnectUrl = baseUrl + "/mcp";
        this.supportEmail = supportEmail == null ? "" : supportEmail;
        this.supportSlackChannel = supportSlackChannel == null ? "" : supportSlackChannel;
        this.serviceClients = List.copyOf(clients);

        Map<String, String> content = new LinkedHashMap<>();
        content.put("howto_refresh_tools", buildRefreshToolsBody());
        for (var client : clients) {
            String toolName = "howto_connect_" + client.getMcpId().replace("-", "_");
            content.put(toolName, client.howTo(dashboardUrl).markdownBody());
        }
        this.connectContentByName = Map.copyOf(content);
    }

    // Build the tool list dynamically per user: reflect connected vs not-connected in descriptions.
    @Override
    public List<ToolEntry> listTools(String userId) {
        var entries = new ArrayList<ToolEntry>();
        entries.add(howToEntry(
                "howto_get_started",
                "Shows which services are connected and which need to be set up. " +
                "Call this first to understand the current connection status before attempting any task."));

        for (var client : serviceClients) {
            String toolName = "howto_connect_" + client.getMcpId().replace("-", "_");
            String desc;
            if (client.isConnected(userId)) {
                desc = "✅ " + client.getMcpName() + " is connected. " +
                        "Call this for details about this integration or reconnection instructions.";
            } else {
                desc = "⚠️ " + client.getMcpName() + " is NOT connected for this user. " +
                        "Tools are visible but will return an error until connected. " +
                        "Call this for connection instructions.";
            }
            entries.add(howToEntry(toolName, desc));
        }

        entries.add(reportUnsupportedEntry());
        entries.add(refreshToolsEntry());
        return List.copyOf(entries);
    }

    private static ToolEntry reportUnsupportedEntry() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", "howto_report_unsupported");
        spec.put("description",
                "Use this when the user asks about a service or integration that is not currently available in the MCP Gateway " +
                "(e.g. BigQuery, Jira, GitHub). Returns instructions for how to request it. " +
                "Pass the name of the service the user asked about.");
        spec.put("inputSchema", Map.of(
                "type", "object",
                "properties", Map.of(
                        "service", Map.of("type", "string", "description", "The name of the service or integration the user asked about")
                ),
                "required", List.of("service")
        ));
        @SuppressWarnings("unchecked")
        var inputSchema = (Map<String, Object>) spec.get("inputSchema");
        var meta = new McpConfig.ToolMeta("howto_report_unsupported", (String) spec.get("description"), inputSchema, true, false);
        return new ToolEntry(spec, meta);
    }

    private static ToolEntry refreshToolsEntry() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", "howto_refresh_tools");
        spec.put("description",
                "Returns guidance on the MCP tool list and connection status. " +
                "Call this when the user asks why a tool call failed, why tools are missing, " +
                "or how to connect a new service.");
        var refreshInputSchema = Map.<String, Object>of("type", "object", "properties", Map.of());
        spec.put("inputSchema", refreshInputSchema);
        var meta = new McpConfig.ToolMeta("howto_refresh_tools", (String) spec.get("description"), refreshInputSchema, true, false);
        return new ToolEntry(spec, meta);
    }

    // Build howto_get_started response dynamically per user showing connected vs. not-connected status.
    @Override
    public ToolCallResult callTool(String userId, String toolName, Map<String, Object> arguments) {
        if ("howto_get_started".equals(toolName)) {
            return new ToolCallResult(buildGetStartedBody(userId), false);
        }
        if ("howto_report_unsupported".equals(toolName)) {
            String service = arguments.getOrDefault("service", "that service").toString();
            return new ToolCallResult(buildUnsupportedBody(service), false);
        }
        if ("howto_refresh_tools".equals(toolName)) {
            return new ToolCallResult(connectContentByName.get("howto_refresh_tools"), false);
        }
        String content = connectContentByName.get(toolName);
        if (content == null) return new ToolCallResult("Unknown how-to tool: " + toolName, true);
        return new ToolCallResult(content, false);
    }

    private String buildGetStartedBody(String userId) {
        var connected = new ArrayList<RemoteMcpClient>();
        var disconnected = new ArrayList<RemoteMcpClient>();
        for (var client : serviceClients) {
            (client.isConnected(userId) ? connected : disconnected).add(client);
        }

        var sb = new StringBuilder();
        sb.append("# MCP Gateway — Service Status\n\n");
        sb.append("All service tools are always visible in this session. ");
        sb.append("Connected services can be called immediately; disconnected ones will return a 'not connected' error until set up.\n\n");

        if (!connected.isEmpty()) {
            sb.append("## ✅ Connected — ready to use\n\n");
            for (var c : connected) {
                var howTo = c.howTo(dashboardUrl);
                sb.append("- **").append(c.getMcpName()).append("** — ").append(howTo.capabilitiesLine()).append("\n");
            }
            sb.append("\n");
        }

        if (!disconnected.isEmpty()) {
            sb.append("## ⚠️ Not Connected — tools visible but will fail until connected\n\n");
            for (var c : disconnected) {
                String connectTool = "howto_connect_" + c.getMcpId().replace("-", "_");
                sb.append("- **").append(c.getMcpName()).append("** — call `").append(connectTool)
                  .append("` for connection instructions, or visit ").append(dashboardUrl).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## How to connect a service\n\n");
        sb.append("1. Open the MCP Gateway dashboard: ").append(dashboardUrl).append("\n");
        sb.append("2. Log in with your Okta SSO account.\n");
        sb.append("3. Click **Connect** next to the service you need and follow the OAuth flow.\n");
        sb.append("4. Tool calls work immediately — no reconnect needed after connecting.\n\n");
        sb.append("If the user asks about a service not listed above, call `howto_report_unsupported` with the service name.\n");
        sb.append("If a tool call fails or the user expects different tools, call `howto_refresh_tools` for guidance.\n");

        return sb.toString();
    }

    private String buildRefreshToolsBody() {
        return """
                # How to refresh your MCP tool list

                The MCP Gateway always shows tools for **all** available services — even ones you \
                haven't connected yet. This means you'll see tools like `slack_search_messages` or \
                `zoho_desk_get_tickets` in the list whether or not you've connected those services.

                ## If a tool call fails with "not connected"

                The tool is visible but the underlying service isn't connected. To fix it:

                1. Open the MCP Gateway dashboard.
                2. Find the service card (e.g. Slack, Zoho Desk) and click **Connect**.
                3. Complete the OAuth flow.
                4. Try the tool call again — no reconnect needed.

                ## If an entirely new service was added to the gateway

                New services added by your admin appear automatically the next time your AI client \
                fetches the tool list. If you're in an active session and don't see them yet, \
                reconnect the MCP server:

                **Claude Code (CLI):**
                ```bash
                claude mcp remove akka-mcp-gateway
                claude mcp add akka-mcp-gateway --transport http %s
                ```

                **Claude Desktop:** Go to Settings → Integrations, remove and re-add Akka MCP Gateway.

                ## If tools are missing right after first setup

                On the very first connection there is nothing cached yet. Connect the gateway once, \
                then reconnect — the second connect will find all tools.
                """.formatted(mcpConnectUrl);
    }

    private static ToolEntry howToEntry(String name, String description) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", name);
        spec.put("description", description);
        var emptySchema = Map.<String, Object>of("type", "object", "properties", Map.of());
        spec.put("inputSchema", emptySchema);
        var meta = new McpConfig.ToolMeta(name, description, emptySchema, true, false);
        return new ToolEntry(spec, meta);
    }

    private String buildUnsupportedBody(String service) {
        return """
                # %s is not currently available

                The MCP Gateway does not have a connector for **%s** yet.

                ## Request it

                To ask for this integration, reach out to the team that maintains the gateway:
                %s
                Include the service name and a brief description of what you'd like to do with it.
                The team will follow up and add it to the gateway if feasible.

                ## In the meantime

                Check `howto_get_started` for a list of services that are already available.
                """.formatted(service, service, buildSupportContactLines());
    }

    /** Renders the configured support contacts, or a generic line when none are configured. */
    private String buildSupportContactLines() {
        var lines = new StringBuilder();
        if (!supportSlackChannel.isBlank()) {
            String channel = supportSlackChannel.startsWith("#") ? supportSlackChannel : "#" + supportSlackChannel;
            String id = channel.substring(1);
            lines.append("\n- **Slack:** [").append(channel).append("](slack://channel?team=&id=").append(id).append(")");
        }
        if (!supportEmail.isBlank()) {
            lines.append("\n- **Email:** ").append(supportEmail);
        }
        if (lines.isEmpty()) {
            return "your gateway administrator.\n";
        }
        return lines.append("\n").toString();
    }

    @Override
    public String getMcpId() { return MCP_ID; }

    @Override
    public String getMcpName() { return MCP_NAME; }

    @Override
    public String getRequiredOktaAppId() { return ""; }

    @Override
    public HowToContent howTo(String dashboardUrl) {
        return new HowToContent(
                "Overview of the MCP Gateway and all available integrations.",
                "Get started, learn available integrations",
                "# MCP Gateway How-To\n\nCall `howto_get_started` for an overview of all integrations.");
    }

    @Override
    public boolean isConnected(String userId) { return true; }

    @Override
    public boolean canHandle(String toolName) {
        return toolName != null && toolName.startsWith("howto_");
    }
}
