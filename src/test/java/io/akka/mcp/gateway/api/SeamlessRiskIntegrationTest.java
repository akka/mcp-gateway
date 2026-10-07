package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.mcp.gateway.application.McpInteractionsByUserView;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.domain.McpConfig;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Seamless.AI's tools go through the same reader/writer permission gate
 * and interaction
 * audit as every other system (FR-009, FR-011), with each call attributed to
 * the calling gateway
 * user (FR-003) even though Seamless itself sees one shared identity.
 *
 * {@code seamless.mcp-url} is pointed at an unroutable local port so a
 * permitted call fails fast
 * on the actual upstream connection (ECONNREFUSED) rather than depending on
 * network access or a
 * real Seamless account — that failure happens strictly after the permission
 * gate this test
 * checks, which is the boundary under test, not the upstream result.
 *
 * <p>Writes are enabled on {@code seamless} in {@link #enableSeamlessWrites}, so the connector-
 * level write gate ({@code AkkaMcpGatewayWriteGateIntegrationTest} covers that one) never
 * intercepts these calls before the reader/writer group check this class is actually testing.
 */
public class SeamlessRiskIntegrationTest extends TestKitSupport {

    private static final String READER_GROUP = "seamless-readers-under-test";
    private static final String WRITER_GROUP = "seamless-writers-under-test";
    private static final String READ_TOOL = "Seamless_search_contacts";
    private static final String WRITE_TOOL = "Seamless_send_email";

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                okta.groups.reader = "%s"
                okta.groups.writer = "%s"
                seamless.api-key = "test-key"
                seamless.mcp-url = "http://127.0.0.1:1"
                """.formatted(READER_GROUP, WRITER_GROUP));
    }

    @BeforeEach
    public void enableSeamlessWrites() {
        GatewayFixtures.enableWritesOn(componentClient, List.of("seamless"));
    }

    private String createMcpToken(String email, List<String> groups) {
        return GatewayFixtures.mcpToken(componentClient, email, groups);
    }

    private void seedTools() {
        var readTool = new McpConfig.ToolMeta(READ_TOOL, "Search contacts",
                Map.of("type", "object", "properties", Map.of()), true, false);
        var writeTool = new McpConfig.ToolMeta(WRITE_TOOL, "Send an email",
                Map.of("type", "object", "properties", Map.of()), false, false);
        componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::register)
                .invoke(new McpConfig("seamless", "Seamless.AI", List.of(readTool, writeTool)));
    }

    private JsonNode callTool(String token, String toolName) throws Exception {
        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", Map.of()));
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + token)
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();
        return JsonSupport.getObjectMapper().readTree(response.body());
    }

    @Test
    public void readerOnlyUser_mayCallReadTool_butNotWriteTool() throws Exception {
        seedTools();
        var token = createMcpToken("seamless-risk-reader@lightbend.com", List.of(READER_GROUP));

        assertThat(callTool(token, READ_TOOL).path("error").isMissingNode())
                .as("read tool should pass the permission gate for a reader")
                .isTrue();

        var writeResult = callTool(token, WRITE_TOOL);
        assertThat(writeResult.path("error").path("message").asText()).contains("Write access not permitted");
    }

    @Test
    public void writerOnlyUser_mayCallWriteTool_butNotReadTool() throws Exception {
        // Reader and writer are independent roles, not tiered (see README "Access and
        // permissions"): holding only mcp-gateway-writer does not also grant read
        // access.
        seedTools();
        var token = createMcpToken("seamless-risk-writer@lightbend.com", List.of(WRITER_GROUP));

        assertThat(callTool(token, WRITE_TOOL).path("error").isMissingNode())
                .as("write tool should pass the permission gate for a writer")
                .isTrue();

        var readResult = callTool(token, READ_TOOL);
        assertThat(readResult.path("error").path("message").asText()).contains("Read access not permitted");
    }

    @Test
    public void userWithBothRoles_mayCallReadAndWriteTools() throws Exception {
        seedTools();
        var token = createMcpToken("seamless-risk-full-access@lightbend.com", List.of(READER_GROUP, WRITER_GROUP));

        assertThat(callTool(token, READ_TOOL).path("error").isMissingNode()).isTrue();
        assertThat(callTool(token, WRITE_TOOL).path("error").isMissingNode()).isTrue();
    }

    @Test
    public void toolWithNoCachedMetadata_defaultsToWrite_soReaderIsRefused() throws Exception {
        // Nothing seeded in the registry for this tool name:
        // McpRegistryEntity::findTool returns
        // empty, and the gateway's safe default (`orElse(true)`) treats it as a write
        // (FR-009).
        var token = createMcpToken("seamless-risk-unknown-tool@lightbend.com", List.of(READER_GROUP));

        var result = callTool(token, "Seamless_some_future_tool");
        assertThat(result.path("error").path("message").asText()).contains("Write access not permitted");
    }

    @Test
    public void permittedCall_isAttributedToTheCallingUserInTheInteractionHistory() throws Exception {
        seedTools();
        var userEmail = "seamless-risk-audit-" + UUID.randomUUID() + "@lightbend.com";
        var token = createMcpToken(userEmail, List.of(WRITER_GROUP));

        callTool(token, WRITE_TOOL);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var history = componentClient.forView()
                    .method(McpInteractionsByUserView::getByUser)
                    .invoke(new McpInteractionsByUserView.UserPageRequest(userEmail, 0, 25));
            assertThat(history.interactions())
                    .anyMatch(i -> i.tool().equals(WRITE_TOOL) && i.mcpId().equals("seamless"));
        });
    }
}
