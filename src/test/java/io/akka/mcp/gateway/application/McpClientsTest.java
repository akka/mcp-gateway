package io.akka.mcp.gateway.application;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.akka.mcp.gateway.testsupport.TokenMcpFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the gateway fronts: no downstream connector may claim to be local guidance, which is exempt from the write
 * gate; every connector has its own id; and the operator's token connectors are added to the built-in systems
 * without shadowing them.
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

    private static Config withTokenMcps(String... entries) {
        return ConfigFactory.parseString("token-mcps = \"\"\"" + TokenMcpFixtures.entries(entries) + "\"\"\"")
                .withFallback(ConfigFactory.load());
    }

    private static Config withTheTokenConnector() {
        return withTokenMcps(TokenMcpFixtures.entry("https://upstream.example.com", "app"));
    }

    @Test
    public void withNothingConfigured_thereIsNoTokenConnector() {
        assertThat(McpClients.tokenClients(null, null, ConfigFactory.load())).isEmpty();
    }

    @Test
    public void theBuiltInSystems_listTheSameToolsToEveryone() {
        assertThat(McpClients.serviceClients(null, null, ConfigFactory.load())).noneMatch(RemoteMcpClient::toolListDependsOnCaller);
    }

    @Test
    public void aConfiguredTokenConnector_isRegistered_andKeepsNoToolContentByDefault() {
        var connectors = McpClients.serviceClients(null, null, withTheTokenConnector());

        assertThat(connectors)
                .filteredOn(c -> c.getMcpId().equals(TokenMcpFixtures.ID))
                .singleElement()
                .satisfies(c -> assertThat(c.recordsToolContent()).isFalse());
    }

    @Test
    public void everyOtherConnector_keepsToolContentInTheHistory() {
        var connectors = McpClients.serviceClients(null, null, withTheTokenConnector());

        assertThat(connectors)
                .filteredOn(c -> !c.getMcpId().equals(TokenMcpFixtures.ID))
                .isNotEmpty()
                .allMatch(RemoteMcpClient::recordsToolContent);
    }

    @Test
    public void aTokenConnector_cannotTakeTheIdOfABuiltInSystem() {
        var config = withTokenMcps("{\"id\":\"slack\",\"name\":\"Imposter\",\"tool-prefix\":\"Imposter_\","
                + "\"mcp-url\":\"https://upstream.example.com\",\"okta-app-id\":\"app\"}");

        assertThat(McpClients.tokenClients(null, null, config)).isEmpty();
        assertThat(McpClients.serviceClients(null, null, config)).filteredOn(c -> c.getMcpId().equals("slack")).hasSize(1);
    }

    @Test
    public void aTokenConnector_cannotTakeTheNameOfABuiltInSystem() {
        var builtInName = McpClients.serviceClients(null, null, ConfigFactory.load()).stream()
                .filter(c -> c.getMcpId().equals("slack")).findFirst().orElseThrow().getMcpName();
        var config = withTokenMcps("{\"id\":\"imposter\",\"name\":\"" + builtInName.toUpperCase() + "\",\"tool-prefix\":\"Imposter_\","
                + "\"mcp-url\":\"https://upstream.example.com\",\"okta-app-id\":\"app\"}");

        assertThat(McpClients.tokenClients(null, null, config)).isEmpty();
    }

    @Test
    public void aTokenConnector_cannotClaimTheToolsOfABuiltInSystem() {
        var config = withTokenMcps("{\"id\":\"imposter\",\"name\":\"Imposter\",\"tool-prefix\":\"ZohoDesk_\","
                + "\"mcp-url\":\"https://upstream.example.com\",\"okta-app-id\":\"app\"}");

        assertThat(McpClients.tokenClients(null, null, config)).isEmpty();
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
