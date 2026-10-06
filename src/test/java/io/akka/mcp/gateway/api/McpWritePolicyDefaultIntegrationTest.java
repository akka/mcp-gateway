package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.McpInteractionsByUserView;
import io.akka.mcp.gateway.testsupport.FakeMcpServer;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.AdvertisedTool;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A fresh deployment, where no admin has saved a selection yet: nothing may write anywhere.
 * Kept apart from the other write-policy tests because they all share, and change, one policy entity.
 */
public class McpWritePolicyDefaultIntegrationTest extends TestKitSupport {

    private static final String ADMIN_GROUP = "mcp-gateway-admin";
    private static final String READER_GROUP = "mcp-gateway-reader";
    private static final String WRITER_GROUP = "mcp-gateway-writer";

    private static final FakeMcpServer SLACK = FakeMcpServer.start().advertising(new AdvertisedTool("slack_post_message", false));

    @AfterAll
    public static void stopFakeUpstream() {
        SLACK.close();
    }

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                slack.mcp-url = "%s"
                okta.groups.admin = "%s"
                okta.groups.reader = "%s"
                okta.groups.writer = "%s"
                """.formatted(SLACK.url(), ADMIN_GROUP, READER_GROUP, WRITER_GROUP));
    }

    @Test
    public void beforeAnyoneSavesAnything_noConnectorHasWritesEnabled() {
        var admin = GatewayFixtures.browserSession(componentClient, "admin@lightbend.com", List.of(ADMIN_GROUP));

        var data = httpClient.GET("/admin/write-access/data")
                .addHeader("Cookie", "SESSION=" + admin)
                .responseBodyAs(McpWritePolicyEndpoint.WritePolicyResponse.class)
                .invoke().body();

        assertThat(data.connectors()).isNotEmpty();
        assertThat(data.connectors()).noneMatch(McpWritePolicyEndpoint.Connector::enabled);
        assertThat(data.updatedBy()).isNull();
    }

    /**
     * The Slack connection is real and the tool is classified as a write, so the refusal can only be
     * the gate: the audit record the gate writes is the positive control for that.
     */
    @Test
    public void beforeAnyoneSavesAnything_aWriterCannotWriteAnywhere() throws Exception {
        var email = "writer-" + UUID.randomUUID() + "@lightbend.com";
        GatewayFixtures.connectSlack(componentClient, email);
        var token = GatewayFixtures.mcpToken(componentClient, email, List.of(READER_GROUP, WRITER_GROUP));
        // Warm the registry from the live tool list so the tool is known to be a write.
        httpClient.POST("/mcp").addHeader("Authorization", "Bearer " + token)
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .responseBodyAs(String.class).invoke();

        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + token)
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", Map.of("name", "slack_post_message", "arguments", Map.of())))
                .responseBodyAs(String.class)
                .invoke();

        var result = JsonSupport.getObjectMapper().readTree(response.body()).path("result");
        assertThat(result.path("isError").asBoolean(false)).isTrue();
        assertThat(SLACK.callsTo("slack_post_message")).isEmpty();
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(componentClient.forView()
                        .method(McpInteractionsByUserView::getByUser)
                        .invoke(new McpInteractionsByUserView.UserPageRequest(email, 0, 25)).interactions())
                        .anySatisfy(e -> {
                            assertThat(e.direction()).isEqualTo("write-rejected");
                            assertThat(e.mcpId()).isEqualTo("slack");
                        }));
    }
}
