package io.akka.mcp.gateway.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.mcp.gateway.domain.UserSession;

import java.time.Instant;
import java.util.List;

/**
 * Indexes session tokens by email so a fresh login can find and invalidate any other
 * still-valid sessions for the same user (browser or MCP client), forcing them to pick up
 * current Okta group membership instead of running on whatever was cached when they were issued.
 */
@Component(id = "user-sessions-by-email")
public class UserSessionsByEmailView extends View {

    public record SessionEntry(String sessionToken, String email, Instant expiresAt) {}

    public record SessionEntries(List<SessionEntry> sessions) {}

    public record ByEmail(String email) {}

    @Consume.FromKeyValueEntity(UserSessionEntity.class)
    public static class SessionsByEmailUpdater extends TableUpdater<SessionEntry> {

        public Effect<SessionEntry> onUpdate(UserSession session) {
            if (session.isEmpty()) {
                // invalidate() resets state to empty rather than deleting the entity;
                // drop the row so the index only reflects still-active sessions.
                return effects().deleteRow();
            }
            var sessionToken = updateContext().eventSubject().orElse("");
            return effects().updateRow(new SessionEntry(sessionToken, session.email(), session.expiresAt()));
        }
    }

    @Query("SELECT * AS sessions FROM user_sessions_by_email WHERE email = :email")
    public QueryEffect<SessionEntries> getByEmail(ByEmail request) {
        return queryResult();
    }
}
