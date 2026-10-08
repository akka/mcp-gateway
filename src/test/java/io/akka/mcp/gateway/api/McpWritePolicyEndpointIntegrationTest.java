package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.StatusCode;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.mcp.gateway.application.McpInteractionsByUserView;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.domain.McpConfig;
import io.akka.mcp.gateway.testsupport.FakeMcpServer;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.AdvertisedTool;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Write access admin page: admins choose which MCPs may write, the choice takes effect
 * immediately, and every change is auditable.
 *
 * All tests share one policy entity, so each one sets the selection it needs before asserting.
 */
public class McpWritePolicyEndpointIntegrationTest extends TestKitSupport {

    private static final String ADMIN_GROUP = "mcp-gateway-admin";
    private static final String READER_GROUP = "mcp-gateway-reader";
    private static final String WRITER_GROUP = "mcp-gateway-writer";
    private static final String USER_EMAIL = "user@lightbend.com";

    private static final FakeMcpServer SLACK = FakeMcpServer.start().advertising(
            new AdvertisedTool("slack_search_messages", true),
            new AdvertisedTool("slack_post_message", false));

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

    private String browserSession(String email, List<String> groups) {
        return GatewayFixtures.browserSession(componentClient, email, groups);
    }

    private String adminSession() {
        return browserSession("admin-" + UUID.randomUUID() + "@lightbend.com", List.of(ADMIN_GROUP));
    }

    private String mcpToken(List<String> groups) {
        return GatewayFixtures.mcpToken(componentClient, USER_EMAIL, groups);
    }

    private void connectSlack() {
        GatewayFixtures.connectSlack(componentClient, USER_EMAIL);
    }

    private McpWritePolicyEndpoint.WritePolicyResponse current(String cookie) {
        return httpClient.GET("/admin/write-access/data")
                .addHeader("Cookie", "SESSION=" + cookie)
                .responseBodyAs(McpWritePolicyEndpoint.WritePolicyResponse.class)
                .invoke().body();
    }

    private static List<String> enabled(McpWritePolicyEndpoint.WritePolicyResponse response) {
        return response.connectors().stream()
                .filter(McpWritePolicyEndpoint.Connector::enabled)
                .map(McpWritePolicyEndpoint.Connector::mcpId)
                .sorted()
                .toList();
    }

    private McpWritePolicyEndpoint.WritePolicyResponse save(String cookie, List<String> ids, Long basedOnVersion) {
        return httpClient.PUT("/admin/write-access")
                .addHeader("Cookie", "SESSION=" + cookie)
                .withRequestBody(new McpWritePolicyEndpoint.UpdateRequest(ids, basedOnVersion))
                .responseBodyAs(McpWritePolicyEndpoint.WritePolicyResponse.class)
                .invoke().body();
    }

    /** Save a selection the way the page does: based on what is currently shown. */
    private McpWritePolicyEndpoint.WritePolicyResponse select(String cookie, List<String> ids) {
        return save(cookie, ids, current(cookie).version());
    }

    private StatusCode statusOfGet(String path, String cookie) {
        var request = httpClient.GET(path);
        if (cookie != null) request = request.addHeader("Cookie", "SESSION=" + cookie);
        return request.invoke().status();
    }

    private StatusCode statusOfPut(String cookie, List<String> ids, Long basedOnVersion) {
        var request = httpClient.PUT("/admin/write-access");
        if (cookie != null) request = request.addHeader("Cookie", "SESSION=" + cookie);
        return request.withRequestBody(new McpWritePolicyEndpoint.UpdateRequest(ids, basedOnVersion)).invoke().status();
    }

    private void seedCachedWriteTool(String mcpId, String mcpName, String toolName) {
        var meta = new McpConfig.ToolMeta(toolName, "seeded", Map.of("type", "object", "properties", Map.of()), false, false);
        componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::register)
                .invoke(new McpConfig(mcpId, mcpName, List.of(meta)));
    }

    private JsonNode rpc(Map<String, Object> request) throws Exception {
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + mcpToken(List.of(READER_GROUP, WRITER_GROUP)))
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();
        return JsonSupport.getObjectMapper().readTree(response.body());
    }

    private List<String> listedTools() throws Exception {
        var names = new ArrayList<String>();
        rpc(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
        return names;
    }

    private JsonNode callSlackPost() throws Exception {
        return rpc(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "slack_post_message", "arguments", Map.of())));
    }

    @Test
    public void unauthenticated_isRejected() {
        assertThat(statusOfGet("/admin/write-access/data", null)).isEqualTo(StatusCodes.UNAUTHORIZED);
        assertThat(statusOfPut(null, List.of(), 0L)).isEqualTo(StatusCodes.UNAUTHORIZED);
    }

    @Test
    public void unauthenticatedPageRequest_isSentToTheLogin() {
        var response = httpClient.GET("/admin/write-access").invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        assertThat(response.httpResponse().getHeader("Location").map(h -> h.value())).hasValue("/login?return_to=%2Fadmin%2Fwrite-access");
    }

    @Test
    public void nonAdmin_cannotSeeOrChangeTheSelection() {
        var writer = browserSession("writer@lightbend.com", List.of(READER_GROUP, WRITER_GROUP));

        assertThat(statusOfGet("/admin/write-access/data", writer)).isEqualTo(StatusCodes.FORBIDDEN);
        assertThat(statusOfGet("/admin/write-access", writer)).isEqualTo(StatusCodes.FORBIDDEN);
        assertThat(statusOfPut(writer, List.of("slack"), 0L)).isEqualTo(StatusCodes.FORBIDDEN);
    }

    @Test
    public void admin_savedSelectionIsReported() {
        var admin = adminSession();

        var saved = select(admin, List.of("google-workspace-gmail"));

        assertThat(enabled(saved)).containsExactly("google-workspace-gmail");
        assertThat(saved.updatedBy()).startsWith("admin-");
        assertThat(enabled(current(admin))).containsExactly("google-workspace-gmail");
    }

    /** Clients cache the tool list and cannot be told it changed, so a saved change must not alter it. */
    @Test
    public void savingASelection_leavesTheToolListUnchanged() throws Exception {
        var admin = adminSession();
        connectSlack();
        seedCachedWriteTool("google-workspace-gmail", "Gmail", "Workspace_Gmail_create_draft");

        select(admin, List.of("google-workspace-gmail"));
        var gmailOnly = new HashSet<>(listedTools());
        select(admin, List.of("slack"));
        var slackOnly = new HashSet<>(listedTools());

        assertThat(gmailOnly).contains("Workspace_Gmail_create_draft", "slack_post_message");
        assertThat(slackOnly).isEqualTo(gmailOnly);
    }

    @Test
    public void clearingTheSelection_stopsWritesThatWereAllowedBefore() throws Exception {
        var admin = adminSession();
        connectSlack();
        SLACK.forgetCalls();
        select(admin, List.of("slack"));
        listedTools();

        callSlackPost();
        assertThat(SLACK.callsTo("slack_post_message")).hasSize(1);

        select(admin, List.of());
        var refused = callSlackPost();

        assertThat(refused.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(SLACK.callsTo("slack_post_message")).hasSize(1);
    }

    @Test
    public void unknownMcpId_isRejected() {
        var admin = adminSession();

        assertThat(statusOfPut(admin, List.of("no-such-mcp"), current(admin).version())).isEqualTo(StatusCodes.BAD_REQUEST);
    }

    @Test
    public void aNullIdInTheSelection_isRejected_evenAlongsideAnUnknownId() {
        var admin = adminSession();
        var version = current(admin).version();

        assertThat(statusOfPut(admin, Arrays.asList("slack", null), version)).isEqualTo(StatusCodes.BAD_REQUEST);
        assertThat(statusOfPut(admin, Arrays.asList("no-such-mcp", null), version)).isEqualTo(StatusCodes.BAD_REQUEST);
    }

    @Test
    public void saveWithoutTheVersionItWasBasedOn_isRejected() {
        var admin = adminSession();

        assertThat(statusOfPut(admin, List.of("slack"), null)).isEqualTo(StatusCodes.BAD_REQUEST);
    }

    @Test
    public void staleSave_isRejectedRatherThanOverwritingANewerChange() {
        var admin = adminSession();
        var other = adminSession();
        var staleVersion = select(admin, List.of("slack")).version();

        select(other, List.of("slack", "google-workspace-docs"));

        assertThat(statusOfPut(admin, List.of("google-workspace-gmail"), staleVersion)).isEqualTo(StatusCodes.CONFLICT);
        assertThat(enabled(current(admin))).containsExactly("google-workspace-docs", "slack");
    }

    @Test
    public void savingTheSameSelectionAgain_changesNothing() {
        var admin = adminSession();
        var first = select(admin, List.of("hubspot", "slack"));

        var again = select(admin, List.of("slack", "hubspot"));

        assertThat(again.version()).isEqualTo(first.version());
        assertThat(again.updatedAt()).isEqualTo(first.updatedAt());
    }

    @Test
    public void everyChange_isAuditedWithWhoAndWhat() {
        var email = "admin-" + UUID.randomUUID() + "@lightbend.com";
        var admin = browserSession(email, List.of(ADMIN_GROUP));
        select(admin, List.of());

        select(admin, List.of("hubspot"));

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var entries = componentClient.forView()
                    .method(McpInteractionsByUserView::getByUser)
                    .invoke(new McpInteractionsByUserView.UserPageRequest(email, 0, 25)).interactions();
            assertThat(entries).anySatisfy(e -> {
                assertThat(e.direction()).isEqualTo("policy-change");
                assertThat(e.tool()).isEqualTo("write-policy");
                assertThat(parsed(e.params()).path("added").asText()).isEqualTo("hubspot");
            });
        });
    }

    private static JsonNode parsed(String json) {
        try {
            return JsonSupport.getObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError("not valid JSON: " + json, e);
        }
    }
}
