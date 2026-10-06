package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.mcp.gateway.application.McpAccessTokenEntity;
import io.akka.mcp.gateway.application.McpInteractionsByUserView;
import io.akka.mcp.gateway.application.McpWritePolicyEntity;
import io.akka.mcp.gateway.application.SlackConnectionEntity;
import io.akka.mcp.gateway.application.UserSessionEntity;
import io.akka.mcp.gateway.testsupport.FakeMcpServer;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.AdvertisedTool;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A downstream MCP is read-only unless an admin has enabled writes on it. That gate belongs to the
 * connector, not the caller: the writer group does not buy a write on a read-only MCP, and is still
 * required on an enabled one.
 *
 * Two fake upstream servers stand in for the downstream MCPs, advertising tools the way the real
 * servers do, so each test runs the whole path: the tool list the upstream sends, how the gateway
 * classifies it, and whether a call reaches the upstream. Okta stands in for a read-only MCP and
 * Slack for an enabled one.
 */
public class AkkaMcpGatewayWriteGateIntegrationTest extends TestKitSupport {

    private static final String WRITER_GROUP = "mcp-gateway-writer";
    private static final String READER_GROUP = "mcp-gateway-reader";
    private static final String USER_EMAIL = "user@lightbend.com";

    private static final FakeMcpServer OKTA = FakeMcpServer.start().advertising(
            new AdvertisedTool("okta_list_users", true),
            new AdvertisedTool("okta_create_user", false));
    private static final FakeMcpServer SLACK = FakeMcpServer.start().advertising(
            new AdvertisedTool("slack_search_messages", true),
            new AdvertisedTool("slack_post_message", false));

    @AfterAll
    public static void stopFakeUpstreams() {
        OKTA.close();
        SLACK.close();
    }

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                okta-admin.mcp-url = "%s"
                slack.mcp-url = "%s"
                okta.groups.reader = "%s"
                okta.groups.writer = "%s"
                """.formatted(OKTA.url(), SLACK.url(), READER_GROUP, WRITER_GROUP));
    }

    /** Writes enabled on Slack only, Slack connected, and the registry warmed from the live tool lists. */
    @BeforeEach
    public void enableWritesOnSlackOnly() throws Exception {
        OKTA.forgetCalls();
        SLACK.forgetCalls();
        var current = componentClient.forKeyValueEntity(McpWritePolicyEntity.ENTITY_ID)
                .method(McpWritePolicyEntity::get)
                .invoke();
        componentClient.forKeyValueEntity(McpWritePolicyEntity.ENTITY_ID)
                .method(McpWritePolicyEntity::select)
                .invoke(new McpWritePolicyEntity.SelectCommand(List.of("slack"), current.version(), "test-setup"));
        connectSlack(USER_EMAIL);
        listedTools(USER_EMAIL, List.of(READER_GROUP, WRITER_GROUP));
    }

    private void connectSlack(String email) {
        componentClient.forKeyValueEntity(email)
                .method(SlackConnectionEntity::initiatePkceOAuth)
                .invoke(new SlackConnectionEntity.InitiateCommand("state-1", "verifier-1", "client-id"));
        componentClient.forKeyValueEntity(email)
                .method(SlackConnectionEntity::storeToken)
                .invoke(new SlackConnectionEntity.StoreTokenCommand(
                        "slack-access", "slack-refresh", Instant.now().plusSeconds(3600), "state-1"));
    }

    /** An MCP client's Bearer token, what {@code POST /mcp} accepts. */
    private String createMcpToken(String email, List<String> groups) {
        var token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(McpAccessTokenEntity::create)
                .invoke(new McpAccessTokenEntity.CreateCommand(
                        email, "User", groups, List.of(), "client-1", Instant.now().plusSeconds(3600)));
        return token;
    }

    private JsonNode rpc(String email, List<String> groups, Map<String, Object> request) throws Exception {
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + createMcpToken(email, groups))
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();
        return JsonSupport.getObjectMapper().readTree(response.body());
    }

    private JsonNode callTool(String email, String toolName, List<String> groups) throws Exception {
        return rpc(email, groups, Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", Map.of())));
    }

    private JsonNode callTool(String toolName, List<String> groups) throws Exception {
        return callTool(USER_EMAIL, toolName, groups);
    }

    private List<String> listedTools(String email, List<String> groups) throws Exception {
        var names = new ArrayList<String>();
        rpc(email, groups, Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
        return names;
    }

    /** The call was performed upstream and came back as a success. */
    private static boolean succeeded(JsonNode response) {
        return response.has("result") && !response.path("result").path("isError").asBoolean(true);
    }

    /** The gateway answered with a tool error of its own. */
    private static boolean refusedByGateway(JsonNode response) {
        return response.path("result").path("isError").asBoolean(false);
    }

    private List<McpInteractionsByUserView.McpInteractionEntry> awaitInteractions(String email, int atLeast) {
        var found = new ArrayList<McpInteractionsByUserView.McpInteractionEntry>();
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var entries = componentClient.forView()
                    .method(McpInteractionsByUserView::getByUser)
                    .invoke(new McpInteractionsByUserView.UserPageRequest(email, 0, 50)).interactions();
            assertThat(entries).hasSizeGreaterThanOrEqualTo(atLeast);
            found.clear();
            found.addAll(entries);
        });
        return found;
    }

    /** The point of the feature: the connector's read-only status outranks the user's writer group. */
    @Test
    public void writeToolOnReadOnlyMcp_isRefusedEvenForWriter_andNeverReachesTheUpstream() throws Exception {
        var response = callTool("okta_create_user", List.of(READER_GROUP, WRITER_GROUP));

        assertThat(refusedByGateway(response)).isTrue();
        assertThat(OKTA.callsTo("okta_create_user")).isEmpty();
    }

    /** An unregistered tool is treated as a write, so on a read-only MCP it must not get through either. */
    @Test
    public void unclassifiedToolOnReadOnlyMcp_neverReachesTheUpstream_andIsRecordedAsUnclassified() throws Exception {
        var email = "unclassified-" + UUID.randomUUID() + "@lightbend.com";

        var response = callTool(email, "okta_never_advertised", List.of(READER_GROUP, WRITER_GROUP));

        assertThat(refusedByGateway(response)).isTrue();
        assertThat(OKTA.callsTo("okta_never_advertised")).isEmpty();
        assertThat(awaitInteractions(email, 1)).anySatisfy(e -> {
            assertThat(e.direction()).isEqualTo("write-rejected");
            assertThat(e.params()).contains("tool-unclassified");
        });
    }

    /** Reads on a read-only MCP are untouched by the gate. */
    @Test
    public void readToolOnReadOnlyMcp_reachesTheUpstream() throws Exception {
        var response = callTool("okta_list_users", List.of(READER_GROUP));

        assertThat(succeeded(response)).isTrue();
        assertThat(OKTA.callsTo("okta_list_users")).hasSize(1);
    }

    /** A write-enabled MCP lets a writer's call through, on the user's own Slack token. */
    @Test
    public void writeToolOnWriteEnabledMcp_reachesTheUpstreamForAWriter_asTheUser() throws Exception {
        var response = callTool("slack_post_message", List.of(READER_GROUP, WRITER_GROUP));

        assertThat(succeeded(response)).isTrue();
        assertThat(SLACK.callsTo("slack_post_message")).singleElement()
                .satisfies(call -> assertThat(call.authorization()).isEqualTo("Bearer slack-access"));
    }

    /** The connector gate is additional, not a replacement: the writer group is still required. */
    @Test
    public void writeToolOnWriteEnabledMcp_stillRequiresTheWriterGroup() throws Exception {
        var response = callTool("slack_post_message", List.of(READER_GROUP));

        assertThat(response.has("error")).isTrue();
        assertThat(response.has("result")).isFalse();
        assertThat(SLACK.callsTo("slack_post_message")).isEmpty();
    }

    /** Advertising tools that will always be refused only wastes the model's turns. */
    @Test
    public void toolsList_hidesWriteToolsOfAReadOnlyMcp_evenFromWriters() throws Exception {
        var names = listedTools(USER_EMAIL, List.of(READER_GROUP, WRITER_GROUP));

        assertThat(names).contains("okta_list_users").doesNotContain("okta_create_user");
    }

    @Test
    public void toolsList_showsWriteToolsOfAnEnabledMcp_onlyToWriters() throws Exception {
        var writerView = listedTools(USER_EMAIL, List.of(READER_GROUP, WRITER_GROUP));
        var readerView = listedTools(USER_EMAIL, List.of(READER_GROUP));

        assertThat(writerView).contains("slack_search_messages", "slack_post_message");
        assertThat(readerView).contains("slack_search_messages").doesNotContain("slack_post_message");
    }

    /** A user who has not connected Slack is served the cached tool list, which must be filtered the same way. */
    @Test
    public void toolsList_filtersTheCachedListToo() throws Exception {
        var notConnected = "no-slack-" + UUID.randomUUID() + "@lightbend.com";

        var writerView = listedTools(notConnected, List.of(READER_GROUP, WRITER_GROUP));
        var readerView = listedTools(notConnected, List.of(READER_GROUP));

        assertThat(writerView).contains("slack_post_message");
        assertThat(readerView).contains("slack_search_messages").doesNotContain("slack_post_message");
        assertThat(writerView).doesNotContain("okta_create_user");
    }

    /** The dashboard badge must not promise writes the signed-in user cannot perform. */
    @Test
    public void mcpAccess_reportsWriteOnlyWhereTheCallerCanActuallyWrite() {
        var writer = writeAllowedById(List.of(READER_GROUP, WRITER_GROUP));
        var reader = writeAllowedById(List.of(READER_GROUP));

        assertThat(writer).containsEntry("slack", true).containsEntry("okta-admin", false);
        assertThat(reader).containsEntry("slack", false);
    }

    private Map<String, Boolean> writeAllowedById(List<String> groups) {
        var session = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(session)
                .method(UserSessionEntity::create)
                .invoke(new UserSessionEntity.CreateCommand(
                        USER_EMAIL, "User", Instant.now().plusSeconds(3600), groups, "", List.of()));
        var access = httpClient.GET("/mcp/access")
                .addHeader("Cookie", "SESSION=" + session)
                .responseBodyAs(AkkaMcpGateway.McpAccessResponse.class)
                .invoke().body();
        var byId = new HashMap<String, Boolean>();
        access.accessible().forEach(e -> byId.put(e.mcpId(), e.writeAllowed()));
        access.inaccessible().forEach(e -> byId.put(e.mcpId(), e.writeAllowed()));
        return byId;
    }

    /** The how-to tools are guidance, not a downstream system, so the write gate must never apply to them. */
    @Test
    public void howToTools_areAvailableToWritersAndReaders_butNotToUsersWithNoRole() throws Exception {
        var writer = callTool("howto_get_started", List.of(READER_GROUP, WRITER_GROUP));
        var reader = callTool("howto_get_started", List.of(READER_GROUP));
        var refresh = callTool("howto_refresh_tools", List.of(READER_GROUP));
        var noRole = callTool("howto_get_started", List.of());

        assertThat(succeeded(writer)).isTrue();
        assertThat(succeeded(reader)).isTrue();
        assertThat(succeeded(refresh)).isTrue();
        assertThat(noRole.has("error")).isTrue();
    }

    @Test
    public void aRefusedWrite_isRecordedAsWriteRejectedWithTheReason() throws Exception {
        var email = "audit-" + UUID.randomUUID() + "@lightbend.com";

        callTool(email, "okta_create_user", List.of(READER_GROUP, WRITER_GROUP));

        assertThat(awaitInteractions(email, 1)).anySatisfy(e -> {
            assertThat(e.direction()).isEqualTo("write-rejected");
            assertThat(e.mcpId()).isEqualTo("okta-admin");
            assertThat(e.tool()).isEqualTo("okta_create_user");
            assertThat(e.params()).contains("mcp-read-only");
        });
    }
}
