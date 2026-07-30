package io.akka.mcp.gateway.api;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.application.McpInteractionEntity;
import io.akka.mcp.gateway.application.McpInteractionsByMcpView;
import io.akka.mcp.gateway.application.McpInteractionsByUserView;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class McpInteractionEndpointIntegrationTest extends TestKitSupport {

    @Test
    public void getByUser_withoutSession_isRejected() {
        assertThrows(Exception.class, () ->
                httpClient.GET("/interactions/user/any-user").responseBodyAs(String.class).invoke()
        );
    }

    @Test
    public void getByMcp_withoutSession_isRejected() {
        assertThrows(Exception.class, () ->
                httpClient.GET("/interactions/by-mcp/salesforce").responseBodyAs(String.class).invoke()
        );
    }

    @Test
    public void updateEscalation_withoutSession_isRejected() {
        assertThrows(Exception.class, () ->
                httpClient.PUT("/interactions/any-id/escalation")
                        .withRequestBody(new McpInteractionEndpoint.UpdateEscalationStatusRequest("REVIEW"))
                        .responseBodyAs(String.class).invoke()
        );
    }

    @Test
    public void record_appearsInUserView() {
        var interactionId = UUID.randomUUID().toString();
        var userId = "user-" + UUID.randomUUID();
        var cmd = new McpInteractionEntity.RecordCommand(userId, "salesforce", "query_accounts", Map.of("limit", "5"), "req");

        componentClient.forEventSourcedEntity(interactionId)
                .method(McpInteractionEntity::record)
                .invoke(cmd);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(McpInteractionsByUserView::getByUser)
                    .invoke(new McpInteractionsByUserView.UserPageRequest(userId, 0, 25));
            assertThat(result.interactions()).hasSize(1);
            assertThat(result.interactions().get(0).interactionId()).isEqualTo(interactionId);
            assertThat(result.interactions().get(0).mcpId()).isEqualTo("salesforce");
            assertThat(result.interactions().get(0).tool()).isEqualTo("query_accounts");
            assertThat(result.interactions().get(0).escalationStatus()).isEmpty();
        });
    }

    @Test
    public void record_appearsInMcpView() {
        var interactionId = UUID.randomUUID().toString();
        var mcpId = "zoho-" + UUID.randomUUID();
        var cmd = new McpInteractionEntity.RecordCommand("user-1", mcpId, "list_tickets", Map.of(), "req");

        componentClient.forEventSourcedEntity(interactionId)
                .method(McpInteractionEntity::record)
                .invoke(cmd);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(McpInteractionsByMcpView::getByMcp)
                    .invoke(new McpInteractionsByMcpView.McpPageRequest(mcpId, 0, 25));
            assertThat(result.interactions()).hasSize(1);
            assertThat(result.interactions().get(0).interactionId()).isEqualTo(interactionId);
            assertThat(result.interactions().get(0).tool()).isEqualTo("list_tickets");
        });
    }

    @Test
    public void updateEscalationStatus_appearsInView() {
        var interactionId = UUID.randomUUID().toString();
        var userId = "user-" + UUID.randomUUID();
        var cmd = new McpInteractionEntity.RecordCommand(userId, "salesforce", "create_lead", Map.of("name", "Acme"), "req");

        componentClient.forEventSourcedEntity(interactionId)
                .method(McpInteractionEntity::record)
                .invoke(cmd);

        componentClient.forEventSourcedEntity(interactionId)
                .method(McpInteractionEntity::updateEscalationStatus)
                .invoke("REVIEW_REQUIRED");

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(McpInteractionsByUserView::getByUser)
                    .invoke(new McpInteractionsByUserView.UserPageRequest(userId, 0, 25));
            assertThat(result.interactions()).hasSize(1);
            assertThat(result.interactions().get(0).escalationStatus()).isEqualTo("REVIEW_REQUIRED");
        });
    }
}
