package io.akka.mcp.gateway.application;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local guidance is exempt from the write gate, so no downstream connector may ever claim to be it:
 * that would let its write tools skip the gate.
 */
public class McpClientsTest {

    @Test
    public void noDownstreamConnector_isLocalGuidance() {
        var connectors = McpClients.serviceClients(null, null, ConfigFactory.load());

        assertThat(connectors).isNotEmpty();
        assertThat(connectors).noneMatch(RemoteMcpClient::isLocalGuidance);
    }

    @Test
    public void everyConnector_hasItsOwnId() {
        var ids = McpClients.serviceClients(null, null, ConfigFactory.load()).stream().map(RemoteMcpClient::getMcpId).toList();

        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    public void theCustomerHubConnector_isRegistered_andKeepsNoToolContent() {
        var connectors = McpClients.serviceClients(null, null, ConfigFactory.load());

        assertThat(connectors)
                .filteredOn(c -> c.getMcpId().equals(CustomerHubMcpClient.MCP_ID))
                .singleElement()
                .satisfies(c -> assertThat(c.recordsToolContent()).isFalse());
    }

    @Test
    public void everyOtherConnector_keepsToolContentInTheHistory() {
        var connectors = McpClients.serviceClients(null, null, ConfigFactory.load());

        assertThat(connectors)
                .filteredOn(c -> !c.getMcpId().equals(CustomerHubMcpClient.MCP_ID))
                .isNotEmpty()
                .allMatch(RemoteMcpClient::recordsToolContent);
    }

    @Test
    public void workspaceClients_areExactlyTheWorkspaceProductsInServiceClients() {
        var config = ConfigFactory.load();
        var workspaceIds = McpClients.workspaceClients(null, config).stream().map(RemoteMcpClient::getMcpId).toList();
        var serviceIds = McpClients.serviceClients(null, null, config).stream().map(RemoteMcpClient::getMcpId).toList();

        assertThat(workspaceIds).containsExactly(
                WorkspaceDriveMcpClient.MCP_ID,
                WorkspaceDocsMcpClient.MCP_ID,
                WorkspaceSheetsMcpClient.MCP_ID,
                WorkspaceGmailMcpClient.MCP_ID,
                WorkspaceCalendarMcpClient.MCP_ID);
        assertThat(serviceIds).containsAll(workspaceIds);
        assertThat(serviceIds.stream().filter(id -> id.startsWith("google-workspace-")))
                .containsExactlyElementsOf(workspaceIds);
    }

    @Test
    public void theHowToClient_isLocalGuidance() {
        var howTo = new HowToMcpClient("http://localhost:9000", "", "", List.of());

        assertThat(howTo.isLocalGuidance()).isTrue();
    }
}
