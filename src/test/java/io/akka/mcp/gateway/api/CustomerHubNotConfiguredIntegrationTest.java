package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.CustomerHubMcpClient;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.application.SlackMcpClient;
import io.akka.mcp.gateway.domain.McpConfig;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Without {@code CUSTOMER_HUB_MCP_URL} and {@code CUSTOMER_HUB_OKTA_APP_ID} the connector is off. */
public class CustomerHubNotConfiguredIntegrationTest extends TestKitSupport {

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                okta.groups.admin = "%s"
                """.formatted(CustomerHubIntegrationTest.ADMIN_GROUP));
    }

    private static final String RETIRED_SYSTEM = "retired-system";

    @Test
    public void connecting_isRefusedAsNotConfigured() {
        var session = GatewayFixtures.browserSession(componentClient, "not-configured@lightbend.com", List.of());

        var response = httpClient.POST("/customer-hub/connect")
                .addHeader("Cookie", "SESSION=" + session)
                .withRequestBody(new CustomerHubEndpoint.ConnectRequest("hubtoken-abc"))
                .invoke();

        assertThat(response.status().intValue()).isEqualTo(503);
        assertThat(response.body().utf8String()).contains("not configured");
    }

    @Test
    public void testing_isRefusedAsNotConfigured() {
        var session = GatewayFixtures.browserSession(componentClient, "not-configured-test@lightbend.com", List.of());

        var response = httpClient.GET("/customer-hub/test").addHeader("Cookie", "SESSION=" + session).invoke();

        assertThat(response.status().intValue()).isEqualTo(503);
    }

    @Test
    public void status_isNotConnected() throws Exception {
        var session = GatewayFixtures.browserSession(componentClient, "not-configured-status@lightbend.com", List.of());

        var response = httpClient.GET("/customer-hub/status").addHeader("Cookie", "SESSION=" + session).invoke();

        assertThat(JsonSupport.getObjectMapper().readTree(response.body().utf8String()).path("connected").asBoolean()).isFalse();
    }

    @Test
    public void theWriteAccessPage_leavesOutTheHub_butKeepsASystemThatIsSetUp() {
        var session = GatewayFixtures.browserSession(componentClient, "not-configured-admin@lightbend.com",
                List.of(CustomerHubIntegrationTest.ADMIN_GROUP));

        var response = httpClient.GET("/admin/write-access/data").addHeader("Cookie", "SESSION=" + session).invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
        assertThat(response.body().utf8String()).contains(SlackMcpClient.MCP_ID).doesNotContain(CustomerHubMcpClient.MCP_ID);
    }

    @Test
    public void theAccessList_leavesOutTheHub_butKeepsASystemThatIsSetUp() throws Exception {
        var session = GatewayFixtures.browserSession(componentClient, "not-configured-access@lightbend.com", List.of());

        var response = httpClient.GET("/mcp/access").addHeader("Cookie", "SESSION=" + session).invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
        assertThat(response.body().utf8String()).contains(SlackMcpClient.MCP_ID).doesNotContain(CustomerHubMcpClient.MCP_ID);
    }

    @Test
    public void aCachedHubTool_isNotListed_whileTheConnectorIsNotSetUp() throws Exception {
        componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::registerAdding)
                .invoke(new McpConfig(CustomerHubMcpClient.MCP_ID, CustomerHubMcpClient.MCP_NAME, List.of(
                        new McpConfig.ToolMeta("CustomerHub_get_request", "Get a request", Map.of("type", "object"), true, false))));
        var mcpToken = GatewayFixtures.mcpToken(componentClient, "not-configured-list@lightbend.com", List.of());

        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + mcpToken)
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .responseBodyAs(String.class)
                .invoke();

        assertThat(response.status().isSuccess()).isTrue();
        assertThat(response.body()).contains("howto_refresh_tools").doesNotContain("CustomerHub_get_request");
    }

    @Test
    public void theToolsPage_leavesOutACachedHubTool_andASystemTheGatewayDoesNotKnow() throws Exception {
        for (var mcpId : List.of(CustomerHubMcpClient.MCP_ID, RETIRED_SYSTEM, SlackMcpClient.MCP_ID)) {
            componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                    .method(McpRegistryEntity::registerAdding)
                    .invoke(new McpConfig(mcpId, mcpId, List.of(
                            new McpConfig.ToolMeta("a_tool", "A tool", Map.of("type", "object"), true, false))));
        }
        var session = GatewayFixtures.browserSession(componentClient, "not-configured-page@lightbend.com", List.of());

        var response = httpClient.GET("/mcp/tools/data").addHeader("Cookie", "SESSION=" + session).invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
        assertThat(response.body().utf8String()).contains(SlackMcpClient.MCP_ID)
                .doesNotContain(CustomerHubMcpClient.MCP_ID).doesNotContain(RETIRED_SYSTEM);
    }

    @Test
    public void theHowToTools_doNotOfferTheHub() throws Exception {
        var mcpToken = GatewayFixtures.mcpToken(componentClient, "not-configured-howto@lightbend.com", List.of());

        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + mcpToken)
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .responseBodyAs(String.class)
                .invoke();

        assertThat(response.body()).contains("howto_connect_slack").doesNotContain("howto_connect_customer_hub");
    }

    @Test
    public void aCall_saysTheHubIsNotSetUp() throws Exception {
        var mcpToken = GatewayFixtures.mcpToken(componentClient, "not-configured-call@lightbend.com", List.of(CustomerHubIntegrationTest.READER_GROUP));

        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + mcpToken)
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", Map.of("name", "CustomerHub_get_request", "arguments", Map.of())))
                .responseBodyAs(String.class)
                .invoke();

        var result = JsonSupport.getObjectMapper().readTree(response.body()).path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").path(0).path("text").asText()).contains("not set up on this gateway");
    }
}
