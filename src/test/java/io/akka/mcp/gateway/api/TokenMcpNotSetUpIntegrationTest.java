package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.application.McpWritePolicyEntity;
import io.akka.mcp.gateway.application.SlackMcpClient;
import io.akka.mcp.gateway.domain.McpConfig;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import io.akka.mcp.gateway.testsupport.TokenMcpFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Without an {@code mcp-url} or an {@code okta-app-id} in its {@code token-mcps} entry the connector is off. */
public class TokenMcpNotSetUpIntegrationTest extends TestKitSupport {

    private static final String RETIRED_SYSTEM = "retired-system";

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                %s
                okta.groups.admin = "%s"
                """.formatted(TokenMcpFixtures.setting("", ""), TokenMcpIntegrationTest.ADMIN_GROUP));
    }

    @Test
    public void connecting_isRefusedAsNotSetUp() {
        var session = GatewayFixtures.browserSession(componentClient, "not-configured@lightbend.com", List.of());

        var response = httpClient.POST(TokenMcpFixtures.path("connect"))
                .addHeader("Cookie", "SESSION=" + session)
                .withRequestBody(new TokenMcpEndpoint.ConnectRequest("upstreamtoken-abc"))
                .invoke();

        assertThat(response.status().intValue()).isEqualTo(503);
        assertThat(response.body().utf8String()).contains("not set up");
    }

    @Test
    public void testing_isRefusedAsNotSetUp() {
        var session = GatewayFixtures.browserSession(componentClient, "not-configured-test@lightbend.com", List.of());

        var response = httpClient.GET(TokenMcpFixtures.path("test")).addHeader("Cookie", "SESSION=" + session).invoke();

        assertThat(response.status().intValue()).isEqualTo(503);
    }

    @Test
    public void anUnknownConnector_isNotFound() {
        var session = GatewayFixtures.browserSession(componentClient, "unknown-connector@lightbend.com", List.of());

        for (var route : List.of("status", "test")) {
            var response = httpClient.GET("/token-mcp/no-such-connector/" + route).addHeader("Cookie", "SESSION=" + session).invoke();
            assertThat(response.status().intValue()).as(route).isEqualTo(404);
        }
        var connect = httpClient.POST("/token-mcp/no-such-connector/connect")
                .addHeader("Cookie", "SESSION=" + session)
                .withRequestBody(new TokenMcpEndpoint.ConnectRequest("upstreamtoken-abc"))
                .invoke();
        assertThat(connect.status().intValue()).isEqualTo(404);
    }

    @Test
    public void savingTheWriteAccessPage_keepsWritesEnabledOnAConnectorThatIsNotSetUp() {
        GatewayFixtures.enableWritesOn(componentClient, List.of(TokenMcpFixtures.ID));
        var admin = GatewayFixtures.browserSession(componentClient, "write-keeper@lightbend.com", List.of(TokenMcpIntegrationTest.ADMIN_GROUP));
        var version = componentClient.forKeyValueEntity(McpWritePolicyEntity.ENTITY_ID).method(McpWritePolicyEntity::get).invoke().version();

        var response = httpClient.PUT("/admin/write-access")
                .addHeader("Cookie", "SESSION=" + admin)
                .withRequestBody(new McpWritePolicyEndpoint.UpdateRequest(List.of(SlackMcpClient.MCP_ID), version))
                .invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
        assertThat(GatewayFixtures.writeEnabledMcpIds(componentClient)).containsExactlyInAnyOrder(SlackMcpClient.MCP_ID, TokenMcpFixtures.ID);
    }

    @Test
    public void disconnecting_whenTheConnectorIsNotSetUp_stillSucceeds() {
        var session = GatewayFixtures.browserSession(componentClient, "off-disconnect@lightbend.com", List.of());

        var response = httpClient.POST(TokenMcpFixtures.path("disconnect")).addHeader("Cookie", "SESSION=" + session).invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
    }

    @Test
    public void savingTheWriteAccessPage_dropsWritesEnabledOnASystemThatIsNotConfiguredAtAll() {
        GatewayFixtures.enableWritesOn(componentClient, List.of(RETIRED_SYSTEM));
        var admin = GatewayFixtures.browserSession(componentClient, "write-dropper@lightbend.com", List.of(TokenMcpIntegrationTest.ADMIN_GROUP));
        var version = componentClient.forKeyValueEntity(McpWritePolicyEntity.ENTITY_ID).method(McpWritePolicyEntity::get).invoke().version();

        var response = httpClient.PUT("/admin/write-access")
                .addHeader("Cookie", "SESSION=" + admin)
                .withRequestBody(new McpWritePolicyEndpoint.UpdateRequest(List.of(SlackMcpClient.MCP_ID), version))
                .invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
        assertThat(GatewayFixtures.writeEnabledMcpIds(componentClient)).containsExactly(SlackMcpClient.MCP_ID);
    }

    @Test
    public void disconnectingAnUnknownConnector_isNotFound() {
        var session = GatewayFixtures.browserSession(componentClient, "unknown-disconnect@lightbend.com", List.of());

        var response = httpClient.POST("/token-mcp/no-such-connector/disconnect").addHeader("Cookie", "SESSION=" + session).invoke();

        assertThat(response.status().intValue()).isEqualTo(404);
    }

    @Test
    public void theConnectorList_leavesOutAConnectorThatIsNotSetUp() {
        var session = GatewayFixtures.browserSession(componentClient, "not-set-up-list@lightbend.com", List.of());

        var response = httpClient.GET("/token-mcp/connectors").addHeader("Cookie", "SESSION=" + session).invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
        assertThat(response.body().utf8String()).isEqualTo("[]");
    }

    @Test
    public void status_isNotConnected() throws Exception {
        var session = GatewayFixtures.browserSession(componentClient, "not-configured-status@lightbend.com", List.of());

        var response = httpClient.GET(TokenMcpFixtures.path("status")).addHeader("Cookie", "SESSION=" + session).invoke();

        assertThat(JsonSupport.getObjectMapper().readTree(response.body().utf8String()).path("connected").asBoolean()).isFalse();
    }

    @Test
    public void theWriteAccessPage_leavesOutTheConnector_butKeepsASystemThatIsSetUp() {
        var session = GatewayFixtures.browserSession(componentClient, "not-configured-admin@lightbend.com",
                List.of(TokenMcpIntegrationTest.ADMIN_GROUP));

        var response = httpClient.GET("/admin/write-access/data").addHeader("Cookie", "SESSION=" + session).invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
        assertThat(response.body().utf8String()).contains(SlackMcpClient.MCP_ID).doesNotContain(TokenMcpFixtures.ID);
    }

    @Test
    public void theAccessList_leavesOutTheConnector_butKeepsASystemThatIsSetUp() throws Exception {
        var session = GatewayFixtures.browserSession(componentClient, "not-configured-access@lightbend.com", List.of());

        var response = httpClient.GET("/mcp/access").addHeader("Cookie", "SESSION=" + session).invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
        assertThat(response.body().utf8String()).contains(SlackMcpClient.MCP_ID).doesNotContain(TokenMcpFixtures.ID);
    }

    @Test
    public void aCachedUpstreamTool_isNotListed_whileTheConnectorIsNotSetUp() throws Exception {
        componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::registerAdding)
                .invoke(new McpConfig(TokenMcpFixtures.ID, TokenMcpFixtures.NAME, List.of(
                        new McpConfig.ToolMeta("TestToken_get_item", "Get an item", Map.of("type", "object"), true, false))));
        var mcpToken = GatewayFixtures.mcpToken(componentClient, "not-configured-list@lightbend.com", List.of());

        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + mcpToken)
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .responseBodyAs(String.class)
                .invoke();

        assertThat(response.status().isSuccess()).isTrue();
        assertThat(response.body()).contains("howto_refresh_tools").doesNotContain("TestToken_get_item");
    }

    @Test
    public void theToolsPage_leavesOutAnUpstreamCachedTool_andASystemTheGatewayDoesNotKnow() throws Exception {
        for (var mcpId : List.of(TokenMcpFixtures.ID, RETIRED_SYSTEM, SlackMcpClient.MCP_ID)) {
            componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                    .method(McpRegistryEntity::registerAdding)
                    .invoke(new McpConfig(mcpId, mcpId, List.of(
                            new McpConfig.ToolMeta("a_tool", "A tool", Map.of("type", "object"), true, false))));
        }
        var session = GatewayFixtures.browserSession(componentClient, "not-configured-page@lightbend.com", List.of());

        var response = httpClient.GET("/mcp/tools/data").addHeader("Cookie", "SESSION=" + session).invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
        assertThat(response.body().utf8String()).contains(SlackMcpClient.MCP_ID)
                .doesNotContain(TokenMcpFixtures.ID).doesNotContain(RETIRED_SYSTEM);
    }

    @Test
    public void theHowToTools_doNotOfferTheConnector() throws Exception {
        var mcpToken = GatewayFixtures.mcpToken(componentClient, "not-configured-howto@lightbend.com", List.of());

        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + mcpToken)
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .responseBodyAs(String.class)
                .invoke();

        assertThat(response.body()).contains("howto_connect_slack").doesNotContain("howto_connect_test_token_service");
    }

    @Test
    public void aCall_saysTheConnectorIsNotSetUp() throws Exception {
        var mcpToken = GatewayFixtures.mcpToken(componentClient, "not-configured-call@lightbend.com", List.of(TokenMcpIntegrationTest.READER_GROUP));

        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + mcpToken)
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", Map.of("name", "TestToken_get_item", "arguments", Map.of())))
                .responseBodyAs(String.class)
                .invoke();

        var result = JsonSupport.getObjectMapper().readTree(response.body()).path("result");
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("content").path(0).path("text").asText()).contains("not set up on this gateway");
    }
}
