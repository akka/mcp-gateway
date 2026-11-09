package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.McpInteractionsByUserView;
import io.akka.mcp.gateway.domain.UserSession;
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

/** A connector set to {@code record-tool-content} keeps the arguments and results of its calls in the history. */
public class TokenMcpRecordingIntegrationTest extends TestKitSupport {

    private static final String GATEWAY_ORIGIN = "http://localhost:9000";
    private static final String APP_ID = "recording-app-under-test";
    private static final String CONNECTOR_ID = "recording-service";
    private static final FakeMcpServer UPSTREAM = FakeMcpServer.start().advertising(new AdvertisedTool("get_item", true));

    @AfterAll
    public static void stopUpstream() {
        UPSTREAM.close();
    }

    @Override
    protected TestKit.Settings testKitSettings() {
        var entry = """
                {"id":"%s","name":"Recording Service","tool-prefix":"Recording_","mcp-url":"%s","okta-app-id":"%s","record-tool-content":true}"""
                .formatted(CONNECTOR_ID, UPSTREAM.url(), APP_ID);
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                token-mcps = \"\"\"[%s]\"\"\"
                mcp.base-url = "%s"
                okta.groups.reader = "%s"
                """.formatted(entry, GATEWAY_ORIGIN, TokenMcpIntegrationTest.READER_GROUP));
    }

    @Test
    public void theHistoryKeepsTheArgumentsAndResultsOfACallToAConnectorThatRecordsContent() throws Exception {
        var token = "recordingtoken-" + UUID.randomUUID();
        UPSTREAM.acceptingOnlyBearer(token);
        GatewayFixtures.enableWritesOn(componentClient, List.of());
        var email = "recording-" + UUID.randomUUID() + "@lightbend.com";
        var apps = List.of(new UserSession.App(APP_ID, "Recording Service"));
        var session = GatewayFixtures.browserSession(componentClient, email, List.of(), apps);
        var mcpToken = GatewayFixtures.mcpToken(componentClient, email, List.of(TokenMcpIntegrationTest.READER_GROUP), apps);
        var connected = httpClient.POST("/token-mcp/" + CONNECTOR_ID + "/connect")
                .addHeader("Cookie", "SESSION=" + session)
                .addHeader("Origin", GATEWAY_ORIGIN)
                .withRequestBody(new TokenMcpEndpoint.ConnectRequest(token))
                .invoke();
        assertThat(connected.status().intValue()).isEqualTo(200);

        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + mcpToken)
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", Map.of("name", "Recording_get_item", "arguments", Map.of("id", "RECORDED-ARGUMENT-789"))))
                .responseBodyAs(String.class)
                .invoke();

        assertThat(JsonSupport.getObjectMapper().readTree(response.body()).path("result").path("isError").asBoolean(false)).isFalse();
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var entries = componentClient.forView()
                    .method(McpInteractionsByUserView::getByUser)
                    .invoke(new McpInteractionsByUserView.UserPageRequest(email, 0, 100))
                    .interactions().stream().filter(e -> e.mcpId().equals(CONNECTOR_ID)).toList();
            assertThat(entries).anySatisfy(entry -> assertThat(entry.params()).contains("RECORDED-ARGUMENT-789"));
            assertThat(entries).anySatisfy(entry -> assertThat(entry.output().orElse("")).contains(FakeMcpServer.HANDLED_PREFIX + "get_item"));
        });
    }
}
