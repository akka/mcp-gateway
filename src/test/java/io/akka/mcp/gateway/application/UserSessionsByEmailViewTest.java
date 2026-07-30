package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.domain.UserSession;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class UserSessionsByEmailViewTest extends TestKitSupport {

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT
                .withKeyValueEntityIncomingMessages(UserSessionEntity.class);
    }

    private UserSession activeSession(String email) {
        return new UserSession(
                email,
                "Test User",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                List.of("mcp-gateway-reader"),
                "id-token",
                List.of());
    }

    @Test
    public void getByEmail_returnsActiveSessionForEmail() {
        var sessions = testKit.getKeyValueEntityIncomingMessages(UserSessionEntity.class);
        String email = "user-" + UUID.randomUUID() + "@example.com";
        String sessionToken = UUID.randomUUID().toString();

        sessions.publish(activeSession(email), sessionToken);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(UserSessionsByEmailView::getByEmail)
                    .invoke(new UserSessionsByEmailView.ByEmail(email));
            assertThat(result.sessions()).hasSize(1);
            assertThat(result.sessions().get(0).sessionToken()).isEqualTo(sessionToken);
            assertThat(result.sessions().get(0).email()).isEqualTo(email);
        });
    }

    @Test
    public void getByEmail_afterInvalidate_removesRow() {
        var sessions = testKit.getKeyValueEntityIncomingMessages(UserSessionEntity.class);
        String email = "user-" + UUID.randomUUID() + "@example.com";
        String sessionToken = UUID.randomUUID().toString();

        sessions.publish(activeSession(email), sessionToken);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(UserSessionsByEmailView::getByEmail)
                    .invoke(new UserSessionsByEmailView.ByEmail(email));
            assertThat(result.sessions()).hasSize(1);
        });

        // invalidate() resets the entity's state to UserSession.empty() rather than deleting it
        sessions.publish(UserSession.empty(), sessionToken);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(UserSessionsByEmailView::getByEmail)
                    .invoke(new UserSessionsByEmailView.ByEmail(email));
            assertThat(result.sessions()).isEmpty();
        });
    }

    @Test
    public void getByEmail_doesNotReturnOtherUsersSessions() {
        var sessions = testKit.getKeyValueEntityIncomingMessages(UserSessionEntity.class);
        String email = "user-" + UUID.randomUUID() + "@example.com";
        String otherEmail = "other-" + UUID.randomUUID() + "@example.com";

        sessions.publish(activeSession(otherEmail), UUID.randomUUID().toString());

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(UserSessionsByEmailView::getByEmail)
                    .invoke(new UserSessionsByEmailView.ByEmail(otherEmail));
            assertThat(result.sessions()).hasSize(1);
        });

        var result = componentClient.forView()
                .method(UserSessionsByEmailView::getByEmail)
                .invoke(new UserSessionsByEmailView.ByEmail(email));
        assertThat(result.sessions()).isEmpty();
    }

    @Test
    public void getByEmail_multipleSessionsForSameEmail_returnsAll() {
        var sessions = testKit.getKeyValueEntityIncomingMessages(UserSessionEntity.class);
        String email = "user-" + UUID.randomUUID() + "@example.com";
        String tokenA = UUID.randomUUID().toString();
        String tokenB = UUID.randomUUID().toString();

        sessions.publish(activeSession(email), tokenA);
        sessions.publish(activeSession(email), tokenB);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var result = componentClient.forView()
                    .method(UserSessionsByEmailView::getByEmail)
                    .invoke(new UserSessionsByEmailView.ByEmail(email));
            assertThat(result.sessions()).hasSize(2);
            assertThat(result.sessions().stream().map(UserSessionsByEmailView.SessionEntry::sessionToken))
                    .containsExactlyInAnyOrder(tokenA, tokenB);
        });
    }
}
