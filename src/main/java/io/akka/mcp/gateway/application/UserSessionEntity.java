package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.mcp.gateway.domain.UserSession;

import java.time.Instant;
import java.util.List;

@Component(id = "user-session")
public class UserSessionEntity extends KeyValueEntity<UserSession> {

    @Override
    public UserSession emptyState() {
        return UserSession.empty();
    }

    public Effect<Done> create(CreateCommand cmd) {
        var state = new UserSession(cmd.email(), cmd.displayName(), Instant.now(), cmd.expiresAt(), cmd.groups(), cmd.idToken(), cmd.apps());
        return effects().updateState(state).thenReply(Done.getInstance());
    }

    public ReadOnlyEffect<UserSession> getSession() {
        return effects().reply(currentState());
    }

    public Effect<Done> invalidate() {
        return effects().updateState(UserSession.empty()).thenReply(Done.getInstance());
    }

    public record CreateCommand(String email, String displayName, Instant expiresAt, List<String> groups, String idToken, List<UserSession.App> apps) {}
}
