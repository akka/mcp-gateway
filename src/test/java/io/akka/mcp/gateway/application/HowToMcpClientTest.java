package io.akka.mcp.gateway.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class HowToMcpClientTest {

    private static final String BASE_URL = "https://mcp.example.com";
    private static final String DASHBOARD_URL = BASE_URL + "/";
    private static final String SUPPORT_EMAIL = "support@example.com";
    private static final String SUPPORT_SLACK = "mcp-help";

    private HowToMcpClient clientWithNoConnectors() {
        return new HowToMcpClient(BASE_URL, SUPPORT_EMAIL, SUPPORT_SLACK, List.of());
    }

    // ---- tool listing ----

    @Test
    public void listTools_alwaysIncludesGetStartedAndReportUnsupported() {
        var client = clientWithNoConnectors();
        var names = client.listTools("any-user").stream()
                .map(e -> (String) e.toolSpec().get("name"))
                .toList();
        assertThat(names).contains("howto_get_started", "howto_report_unsupported");
    }

    @Test
    public void listTools_includesHowToConnectToolForEachClient() {
        var stub = stubClient("zoho-desk", "Zoho Desk");
        var client = new HowToMcpClient(BASE_URL, SUPPORT_EMAIL, SUPPORT_SLACK, List.of(stub));
        var names = client.listTools("any-user").stream()
                .map(e -> (String) e.toolSpec().get("name"))
                .toList();
        assertThat(names).contains("howto_connect_zoho_desk");
    }

    @Test
    public void listTools_replacesHyphensWithUnderscoresInToolName() {
        var stub = stubClient("google-drive", "Google Drive");
        var client = new HowToMcpClient(BASE_URL, SUPPORT_EMAIL, SUPPORT_SLACK, List.of(stub));
        var names = client.listTools("any-user").stream()
                .map(e -> (String) e.toolSpec().get("name"))
                .toList();
        assertThat(names).contains("howto_connect_google_drive");
        assertThat(names).noneMatch(n -> n.contains("-"));
    }

    // ---- canHandle ----

    @Test
    public void canHandle_returnsTrueForHowToPrefix() {
        var client = clientWithNoConnectors();
        assertThat(client.canHandle("howto_get_started")).isTrue();
        assertThat(client.canHandle("howto_connect_zoho_desk")).isTrue();
        assertThat(client.canHandle("howto_report_unsupported")).isTrue();
    }

    @Test
    public void canHandle_returnsFalseForOtherTools() {
        var client = clientWithNoConnectors();
        assertThat(client.canHandle("zoho_list_tickets")).isFalse();
        assertThat(client.canHandle(null)).isFalse();
        assertThat(client.canHandle("")).isFalse();
    }

    // ---- howto_get_started ----

    @Test
    public void callTool_getStarted_returnsMarkdownWithDashboardUrl() {
        var client = clientWithNoConnectors();
        var result = client.callTool("user", "howto_get_started", Map.of());
        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("MCP Gateway");
        assertThat(result.text()).contains(DASHBOARD_URL);
    }

    @Test
    public void callTool_getStarted_mentionsReportUnsupported() {
        var client = clientWithNoConnectors();
        var result = client.callTool("user", "howto_get_started", Map.of());
        assertThat(result.text()).contains("howto_report_unsupported");
    }

    @Test
    public void callTool_getStarted_listsEachConnectorCapability() {
        var stub = stubClient("zoho-desk", "Zoho Desk");
        var client = new HowToMcpClient(BASE_URL, SUPPORT_EMAIL, SUPPORT_SLACK, List.of(stub));
        var result = client.callTool("user", "howto_get_started", Map.of());
        assertThat(result.text()).contains("Zoho Desk");
    }

    // ---- howto_connect_* ----

    @Test
    public void callTool_howToConnect_returnsConnectorMarkdown() {
        var stub = stubClient("zoho-desk", "Zoho Desk");
        var client = new HowToMcpClient(BASE_URL, SUPPORT_EMAIL, SUPPORT_SLACK, List.of(stub));
        var result = client.callTool("user", "howto_connect_zoho_desk", Map.of());
        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("zoho-desk how-to");
    }

    // ---- howto_refresh_tools ----

    @Test
    public void listTools_alwaysIncludesRefreshTools() {
        var client = clientWithNoConnectors();
        var names = client.listTools("any-user").stream()
                .map(e -> (String) e.toolSpec().get("name"))
                .toList();
        assertThat(names).contains("howto_refresh_tools");
    }

    @Test
    public void callTool_refreshTools_returnsReconnectInstructions() {
        var client = clientWithNoConnectors();
        var result = client.callTool("user", "howto_refresh_tools", Map.of());
        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("reconnect");
        assertThat(result.text()).contains("claude mcp");
    }

    @Test
    public void callTool_getStarted_mentionsRefreshTools() {
        var client = clientWithNoConnectors();
        var result = client.callTool("user", "howto_get_started", Map.of());
        assertThat(result.text()).contains("howto_refresh_tools");
    }

    // ---- howto_report_unsupported ----

    @Test
    public void callTool_reportUnsupported_includesServiceNameInResponse() {
        var client = clientWithNoConnectors();
        var result = client.callTool("user", "howto_report_unsupported", Map.of("service", "BigQuery"));
        assertThat(result.isError()).isFalse();
        assertThat(result.text()).contains("BigQuery");
    }

    @Test
    public void callTool_reportUnsupported_includesConfiguredContactChannels() {
        var client = clientWithNoConnectors();
        var result = client.callTool("user", "howto_report_unsupported", Map.of("service", "Jira"));
        assertThat(result.text()).contains(SUPPORT_EMAIL);
        assertThat(result.text()).contains("#" + SUPPORT_SLACK);
    }

    @Test
    public void callTool_reportUnsupported_fallsBackToGenericContactWhenUnconfigured() {
        var client = new HowToMcpClient(BASE_URL, "", "", List.of());
        var result = client.callTool("user", "howto_report_unsupported", Map.of("service", "Jira"));
        assertThat(result.text()).contains("gateway administrator");
    }

    @Test
    public void callTool_reportUnsupported_defaultsServiceNameWhenMissing() {
        var client = clientWithNoConnectors();
        var result = client.callTool("user", "howto_report_unsupported", Map.of());
        assertThat(result.isError()).isFalse();
        assertThat(result.text()).isNotBlank();
    }

    // ---- unknown tool ----

    @Test
    public void callTool_unknownTool_returnsError() {
        var client = clientWithNoConnectors();
        var result = client.callTool("user", "howto_nonexistent", Map.of());
        assertThat(result.isError()).isTrue();
    }

    // ---- helpers ----

    private static RemoteMcpClient stubClient(String mcpId, String mcpName) {
        return new RemoteMcpClient() {
            @Override public String getMcpId() { return mcpId; }
            @Override public String getMcpName() { return mcpName; }
            @Override public String getRequiredOktaAppId() { return ""; }
            @Override public boolean isConnected(String userId) { return true; }
            @Override public boolean canHandle(String toolName) { return false; }
            @Override public List<ToolEntry> listTools(String userId) { return List.of(); }
            @Override public ToolCallResult callTool(String userId, String toolName, Map<String, Object> arguments) {
                return new ToolCallResult("", false);
            }
            @Override public HowToContent howTo(String dashboardUrl) {
                return new HowToContent(
                        "connect " + mcpId + " tool description",
                        mcpName + " capabilities",
                        mcpId + " how-to markdown body");
            }
        };
    }
}
