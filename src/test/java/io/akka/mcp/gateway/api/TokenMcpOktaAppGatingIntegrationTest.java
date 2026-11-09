package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.application.SlackMcpClient;
import io.akka.mcp.gateway.domain.McpConfig;
import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import io.akka.mcp.gateway.testsupport.TokenMcpFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Once an operator sets a connector's {@code okta-app-id}, the connector is gated by that Okta application
 * assignment like every other system.
 */
public class TokenMcpOktaAppGatingIntegrationTest extends TestKitSupport {

    private static final String OKTA_APP_ID = TokenMcpIntegrationTest.APP_ID;
    private static final String TOOL_NAME = "TestToken_get_item";

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig(TokenMcpFixtures.setting("http://127.0.0.1:1", OKTA_APP_ID));
    }

    private void seedCachedUpstreamTool() {
        var cachedTool = new McpConfig.ToolMeta(
                TOOL_NAME, "Get an item", Map.of("type", "object", "properties", Map.of()), true, false);
        componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::register)
                .invoke(new McpConfig(TokenMcpFixtures.ID, TokenMcpFixtures.NAME, List.of(cachedTool)));
    }

    private JsonNode rpc(String mcpToken, Map<String, Object> request) throws Exception {
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + mcpToken)
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();
        return JsonSupport.getObjectMapper().readTree(response.body());
    }

    private List<String> listedTools(String mcpToken) throws Exception {
        var names = new ArrayList<String>();
        rpc(mcpToken, Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
        return names;
    }

    @Test
    public void disconnecting_withoutTheApp_stillSucceeds() {
        var session = GatewayFixtures.browserSession(componentClient, "no-app-disconnect@lightbend.com", List.of());

        var response = httpClient.POST(TokenMcpFixtures.path("disconnect")).addHeader("Cookie", "SESSION=" + session).invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
    }

    @Test
    public void toolsList_hidesTheUpstreamToolsForAUserWithoutTheApp() throws Exception {
        seedCachedUpstreamTool();
        var token = GatewayFixtures.mcpToken(componentClient, "no-app@lightbend.com", List.of());

        assertThat(listedTools(token)).doesNotContain(TOOL_NAME);
    }

    @Test
    public void toolsList_listsTheUpstreamToolsForAUserWithTheApp() throws Exception {
        seedCachedUpstreamTool();
        var token = GatewayFixtures.mcpToken(componentClient, "with-app@lightbend.com", List.of(),
                List.of(new UserSession.App(OKTA_APP_ID, TokenMcpFixtures.NAME)));

        assertThat(listedTools(token)).contains(TOOL_NAME);
    }

    private List<String> systemsOnTheToolsPage(String email, List<UserSession.App> apps) throws Exception {
        var session = GatewayFixtures.browserSession(componentClient, email, List.of(), apps);
        var response = httpClient.GET("/mcp/tools/data").addHeader("Cookie", "SESSION=" + session).invoke();
        var mcpIds = new ArrayList<String>();
        JsonSupport.getObjectMapper().readTree(response.body().utf8String()).path("configs")
                .forEach(config -> mcpIds.add(config.path("mcpId").asText()));
        return mcpIds;
    }

    @Test
    public void theToolsPage_leavesOutTheUpstreamForAUserWithoutTheApp_andShowsItToAUserWithIt() throws Exception {
        seedCachedUpstreamTool();
        componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::registerAdding)
                .invoke(new McpConfig(SlackMcpClient.MCP_ID, "Slack", List.of(
                        new McpConfig.ToolMeta("slack_search_messages", "Search", Map.of("type", "object"), true, false))));

        assertThat(systemsOnTheToolsPage("tools-page-no-app@lightbend.com", List.of()))
                .contains(SlackMcpClient.MCP_ID).doesNotContain(TokenMcpFixtures.ID);
        assertThat(systemsOnTheToolsPage("tools-page-with-app@lightbend.com",
                List.of(new UserSession.App(OKTA_APP_ID, TokenMcpFixtures.NAME)))).contains(TokenMcpFixtures.ID);
    }

    @Test
    public void toolsCall_refusesTheUpstreamToolForAUserWithoutTheApp_likeAToolThatDoesNotExist() throws Exception {
        seedCachedUpstreamTool();
        var token = GatewayFixtures.mcpToken(componentClient, "no-app-call@lightbend.com", List.of());

        var response = rpc(token, Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", TOOL_NAME, "arguments", Map.of())));

        assertThat(response.path("error").path("message").asText()).contains("No MCP client can handle tool");
    }
}
