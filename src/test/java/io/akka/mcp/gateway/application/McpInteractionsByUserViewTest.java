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

public class McpInteractionsByUserViewTest extends TestKitSupport {

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT
                .withEventSourcedEntityIncomingMessages(McpInteractionEntity.class);
    }

    @Test
    public void getByUser_returnsInteractionsForUser() {
        var events = testKit.getEventSourcedEntityIncomingMessages(McpInteractionEntity.class);
        String interactionId = UUID.randomUUID().toString();
        String userId = "user-" + UUID.randomUUID();

        events.publish(
                new McpInteractionEvent.InteractionRecorded(
                        userId, "salesforce", "query_accounts", Map.of("limit", "10"), Instant.now(), "req", null),
                interactionId);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(McpInteractionsByUserView::getByUser)
                    .invoke(new McpInteractionsByUserView.UserPageRequest(userId, 0, 10));
            assertThat(result.interactions()).hasSize(1);
            assertThat(result.interactions().get(0).userId()).isEqualTo(userId);
            assertThat(result.interactions().get(0).mcpId()).isEqualTo("salesforce");
            assertThat(result.interactions().get(0).tool()).isEqualTo("query_accounts");
            assertThat(result.interactions().get(0).direction()).isEqualTo("req");
            assertThat(result.totalCount()).isEqualTo(1);
        });
    }

    @Test
    public void getByUser_withEscalationUpdate_reflectsNewStatus() {
        var events = testKit.getEventSourcedEntityIncomingMessages(McpInteractionEntity.class);
        String interactionId = UUID.randomUUID().toString();
        String userId = "user-" + UUID.randomUUID();

        events.publish(
                new McpInteractionEvent.InteractionRecorded(
                        userId, "zoho", "list_tickets", Map.of(), Instant.now(), "req", null),
                interactionId);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(McpInteractionsByUserView::getByUser)
                    .invoke(new McpInteractionsByUserView.UserPageRequest(userId, 0, 10));
            assertThat(result.interactions()).hasSize(1);
        });

        events.publish(new McpInteractionEvent.EscalationStatusUpdated("escalated"), interactionId);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(McpInteractionsByUserView::getByUser)
                    .invoke(new McpInteractionsByUserView.UserPageRequest(userId, 0, 10));
            assertThat(result.interactions().get(0).escalationStatus()).isEqualTo("escalated");
        });
    }

    @Test
    public void getByUser_doesNotReturnOtherUsersInteractions() {
        var events = testKit.getEventSourcedEntityIncomingMessages(McpInteractionEntity.class);
        String userId = "user-" + UUID.randomUUID();
        String otherUserId = "user-" + UUID.randomUUID();

        events.publish(
                new McpInteractionEvent.InteractionRecorded(
                        otherUserId, "salesforce", "query_accounts", Map.of(), Instant.now(), "req", null),
                UUID.randomUUID().toString());

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(McpInteractionsByUserView::getByUser)
                    .invoke(new McpInteractionsByUserView.UserPageRequest(otherUserId, 0, 10));
            assertThat(result.interactions()).hasSize(1);
        });

        var result = componentClient.forView()
                .method(McpInteractionsByUserView::getByUser)
                .invoke(new McpInteractionsByUserView.UserPageRequest(userId, 0, 10));
        assertThat(result.interactions()).isEmpty();
    }

    @Test
    public void getAll_returnsAllInteractions() {
        var events = testKit.getEventSourcedEntityIncomingMessages(McpInteractionEntity.class);
        String userId1 = "user-" + UUID.randomUUID();
        String userId2 = "user-" + UUID.randomUUID();

        events.publish(
                new McpInteractionEvent.InteractionRecorded(
                        userId1, "salesforce", "tool_a", Map.of(), Instant.now(), "req", null),
                UUID.randomUUID().toString());
        events.publish(
                new McpInteractionEvent.InteractionRecorded(
                        userId2, "zoho", "tool_b", Map.of(), Instant.now(), "req", null),
                UUID.randomUUID().toString());

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(McpInteractionsByUserView::getAll)
                    .invoke(new McpInteractionsByUserView.PageRequest(0, 100));
            assertThat(result.interactions().stream().anyMatch(i -> i.userId().equals(userId1))).isTrue();
            assertThat(result.interactions().stream().anyMatch(i -> i.userId().equals(userId2))).isTrue();
        });
    }
}
