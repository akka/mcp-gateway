package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.domain.McpInteractionEvent;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class McpInteractionsByMcpViewTest extends TestKitSupport {

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT
                .withEventSourcedEntityIncomingMessages(McpInteractionEntity.class);
    }

    @Test
    public void getByMcp_returnsInteractionsForMcpServer() {
        var events = testKit.getEventSourcedEntityIncomingMessages(McpInteractionEntity.class);
        String interactionId = UUID.randomUUID().toString();
        String mcpId = "salesforce-" + UUID.randomUUID();

        events.publish(
                new McpInteractionEvent.InteractionRecorded(
                        "user-1", mcpId, "query_accounts", Map.of("limit", "5"), Instant.now(), "req", null),
                interactionId);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(McpInteractionsByMcpView::getByMcp)
                    .invoke(new McpInteractionsByMcpView.McpPageRequest(mcpId, 0, 10));
            assertThat(result.interactions()).hasSize(1);
            assertThat(result.interactions().get(0).mcpId()).isEqualTo(mcpId);
            assertThat(result.interactions().get(0).userId()).isEqualTo("user-1");
            assertThat(result.interactions().get(0).tool()).isEqualTo("query_accounts");
            assertThat(result.interactions().get(0).direction()).isEqualTo("req");
            assertThat(result.totalCount()).isEqualTo(1);
        });
    }

    @Test
    public void getByMcp_withEscalationUpdate_reflectsNewStatus() {
        var events = testKit.getEventSourcedEntityIncomingMessages(McpInteractionEntity.class);
        String interactionId = UUID.randomUUID().toString();
        String mcpId = "zoho-" + UUID.randomUUID();

        events.publish(
                new McpInteractionEvent.InteractionRecorded(
                        "user-1", mcpId, "list_tickets", Map.of(), Instant.now(), "req", null),
                interactionId);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(McpInteractionsByMcpView::getByMcp)
                    .invoke(new McpInteractionsByMcpView.McpPageRequest(mcpId, 0, 10));
            assertThat(result.interactions()).hasSize(1);
        });

        events.publish(new McpInteractionEvent.EscalationStatusUpdated("escalated"), interactionId);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(McpInteractionsByMcpView::getByMcp)
                    .invoke(new McpInteractionsByMcpView.McpPageRequest(mcpId, 0, 10));
            assertThat(result.interactions().get(0).escalationStatus()).isEqualTo("escalated");
        });
    }

    @Test
    public void getByMcp_doesNotReturnOtherMcpInteractions() {
        var events = testKit.getEventSourcedEntityIncomingMessages(McpInteractionEntity.class);
        String mcpId = "salesforce-" + UUID.randomUUID();
        String otherMcpId = "zoho-" + UUID.randomUUID();

        events.publish(
                new McpInteractionEvent.InteractionRecorded(
                        "user-1", otherMcpId, "list_tickets", Map.of(), Instant.now(), "req", null),
                UUID.randomUUID().toString());

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(McpInteractionsByMcpView::getByMcp)
                    .invoke(new McpInteractionsByMcpView.McpPageRequest(otherMcpId, 0, 10));
            assertThat(result.interactions()).hasSize(1);
        });

        var result = componentClient.forView()
                .method(McpInteractionsByMcpView::getByMcp)
                .invoke(new McpInteractionsByMcpView.McpPageRequest(mcpId, 0, 10));
        assertThat(result.interactions()).isEmpty();
    }

    @Test
    public void getByMcp_multipleInteractions_returnsPaginatedResults() {
        var events = testKit.getEventSourcedEntityIncomingMessages(McpInteractionEntity.class);
        String mcpId = "salesforce-" + UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            events.publish(
                    new McpInteractionEvent.InteractionRecorded(
                            "user-1", mcpId, "tool_" + i, Map.of(), Instant.now(), "req", null),
                    UUID.randomUUID().toString());
        }

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(McpInteractionsByMcpView::getByMcp)
                    .invoke(new McpInteractionsByMcpView.McpPageRequest(mcpId, 0, 10));
            assertThat(result.interactions()).hasSize(3);
            assertThat(result.totalCount()).isEqualTo(3);
        });

        var page = componentClient.forView()
                .method(McpInteractionsByMcpView::getByMcp)
                .invoke(new McpInteractionsByMcpView.McpPageRequest(mcpId, 0, 2));
        assertThat(page.interactions()).hasSize(2);
    }
}
