package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.McpAccessTokenEntity;
import io.akka.mcp.gateway.application.McpInteractionsByUserView;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.application.SlackConnectionEntity;
import io.akka.mcp.gateway.application.UserSessionEntity;
import io.akka.mcp.gateway.domain.McpConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    private static final String DEAD_URL = "http://localhost:1/mcp";

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                slack.mcp-url = "%s"
                okta.groups.admin = "%s"
                okta.groups.reader = "%s"
                okta.groups.writer = "%s"
                """.formatted(DEAD_URL, ADMIN_GROUP, READER_GROUP, WRITER_GROUP));
    }

    private String browserSession(String email, List<String> groups) {
        var token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(UserSessionEntity::create)
                .invoke(new UserSessionEntity.CreateCommand(
                        email, "User", Instant.now().plusSeconds(3600), groups, "", List.of()));
        return token;
    }

    private String adminSession() {
        return browserSession("admin-" + UUID.randomUUID() + "@lightbend.com", List.of(ADMIN_GROUP));
    }

    private String mcpToken(List<String> groups) {
        var token = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(token)
                .method(McpAccessTokenEntity::create)
                .invoke(new McpAccessTokenEntity.CreateCommand(
                        "user@lightbend.com", "User", groups, List.of(), "client-1", Instant.now().plusSeconds(3600)));
        return token;
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

    private void seedWriteTool(String mcpId, String mcpName, String toolName) {
        var meta = new McpConfig.ToolMeta(toolName, "seeded", Map.of("type", "object", "properties", Map.of()), false, false);
        componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::register)
                .invoke(new McpConfig(mcpId, mcpName, List.of(meta)));
    }

    private List<String> listedTools(List<String> groups) throws Exception {
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + mcpToken(groups))
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .responseBodyAs(String.class)
                .invoke();
        var names = new ArrayList<String>();
        JsonSupport.getObjectMapper().readTree(response.body()).path("result").path("tools")
                .forEach(t -> names.add(t.path("name").asText()));
        return names;
    }

    @Test
    public void unauthenticated_isRejected() {
        assertThrows(Exception.class, () -> httpClient.GET("/admin/write-access/data").responseBodyAs(String.class).invoke());
        assertThrows(Exception.class, () -> httpClient.PUT("/admin/write-access")
                .withRequestBody(new McpWritePolicyEndpoint.UpdateRequest(List.of(), 0L))
                .responseBodyAs(String.class).invoke());
    }

    @Test
    public void nonAdmin_cannotReadOrChangeTheSelection() {
        var writer = browserSession("writer@lightbend.com", List.of(READER_GROUP, WRITER_GROUP));

        var read = assertThrows(Exception.class, () -> current(writer));
        var change = assertThrows(Exception.class, () -> save(writer, List.of("slack"), 0L));

        assertThat(read.getMessage()).contains("403");
        assertThat(change.getMessage()).contains("403");
    }

    @Test
    public void admin_savedSelectionIsReported() {
        var admin = adminSession();

        var saved = select(admin, List.of("google-workspace-gmail"));

        assertThat(enabled(saved)).containsExactly("google-workspace-gmail");
        assertThat(saved.updatedBy()).startsWith("admin-");
        assertThat(enabled(current(admin))).containsExactly("google-workspace-gmail");
    }

    @Test
    public void savedSelection_takesEffectImmediately_inTheToolList() throws Exception {
        var admin = adminSession();
        seedWriteTool("slack", "Slack", "slack_post_message");
        seedWriteTool("google-workspace-gmail", "Gmail", "Workspace_Gmail_create_draft");

        select(admin, List.of("google-workspace-gmail"));
        var gmailOnly = listedTools(List.of(READER_GROUP, WRITER_GROUP));
        select(admin, List.of("slack"));
        var slackOnly = listedTools(List.of(READER_GROUP, WRITER_GROUP));

        assertThat(gmailOnly).contains("Workspace_Gmail_create_draft").doesNotContain("slack_post_message");
        assertThat(slackOnly).contains("slack_post_message").doesNotContain("Workspace_Gmail_create_draft");
    }

    @Test
    public void clearingTheSelection_refusesWritesThatWereAllowedBefore() throws Exception {
        var admin = adminSession();
        componentClient.forKeyValueEntity("user@lightbend.com")
                .method(SlackConnectionEntity::initiatePkceOAuth)
                .invoke(new SlackConnectionEntity.InitiateCommand("state-1", "verifier-1", "client-id"));
        componentClient.forKeyValueEntity("user@lightbend.com")
                .method(SlackConnectionEntity::storeToken)
                .invoke(new SlackConnectionEntity.StoreTokenCommand(
                        "slack-access", "slack-refresh", Instant.now().plusSeconds(3600), "state-1"));
        seedWriteTool("slack", "Slack", "slack_post_message");

        select(admin, List.of("slack"));
        var allowed = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + mcpToken(List.of(READER_GROUP, WRITER_GROUP)))
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", Map.of("name", "slack_post_message", "arguments", Map.of())))
                .responseBodyAs(String.class)
                .invoke();
        select(admin, List.of());
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + mcpToken(List.of(READER_GROUP, WRITER_GROUP)))
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", Map.of("name", "slack_post_message", "arguments", Map.of())))
                .responseBodyAs(String.class)
                .invoke();

        var text = JsonSupport.getObjectMapper().readTree(response.body())
                .path("result").path("content").get(0).path("text").asText();
        assertThat(JsonSupport.getObjectMapper().readTree(allowed.body()).path("result").path("content").get(0).path("text").asText())
                .doesNotContain("read-only");
        assertThat(text).contains("read-only");
    }

    @Test
    public void unknownMcpId_isRejected() {
        var admin = adminSession();

        var ex = assertThrows(Exception.class, () -> save(admin, List.of("no-such-mcp"), current(admin).version()));

        assertThat(ex.getMessage()).contains("400");
    }

    @Test
    public void saveWithoutTheVersionItWasBasedOn_isRejected() {
        var admin = adminSession();

        var ex = assertThrows(Exception.class, () -> save(admin, List.of("slack"), null));

        assertThat(ex.getMessage()).contains("400");
    }

    @Test
    public void staleSave_isRejectedRatherThanOverwritingANewerChange() {
        var admin = adminSession();
        var other = adminSession();
        var staleVersion = select(admin, List.of("slack")).version();

        select(other, List.of("slack", "google-workspace-docs"));
        var ex = assertThrows(Exception.class, () -> save(admin, List.of("google-workspace-gmail"), staleVersion));

        assertThat(ex.getMessage()).contains("409");
        assertThat(enabled(current(admin))).containsExactly("google-workspace-docs", "slack");
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
                assertThat(e.params()).contains("\"added\":\"hubspot\"");
            });
        });
    }

    @Test
    public void unchangedSave_isNotAudited() {
        var email = "admin-" + UUID.randomUUID() + "@lightbend.com";
        var admin = browserSession(email, List.of(ADMIN_GROUP));
        select(admin, List.of("slack"));
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(policyChangesBy(email)).hasSize(1));

        select(admin, List.of("slack"));

        Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(policyChangesBy(email)).hasSize(1));
    }

    private List<McpInteractionsByUserView.McpInteractionEntry> policyChangesBy(String email) {
        return componentClient.forView()
                .method(McpInteractionsByUserView::getByUser)
                .invoke(new McpInteractionsByUserView.UserPageRequest(email, 0, 25)).interactions().stream()
                .filter(e -> "policy-change".equals(e.direction()))
                .toList();
    }
}
