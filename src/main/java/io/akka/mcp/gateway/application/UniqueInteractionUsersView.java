package io.akka.mcp.gateway.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;

import java.util.List;

@Component(id = "unique-interaction-users-view")
public class UniqueInteractionUsersView extends View {

    public record UserEntry(String userId) {}

    public record UserList(List<String> items) {}

    @Consume.FromKeyValueEntity(UniqueInteractionUserEntity.class)
    public static class Updater extends TableUpdater<UserEntry> {
        public Effect<UserEntry> onUpdate(UniqueInteractionUserEntity.State state) {
            return effects().updateRow(new UserEntry(state.userId()));
        }
    }

    @Query("SELECT userId AS items FROM unique_interaction_users_view ORDER BY userId ASC")
    public QueryEffect<UserList> getAll() {
        return queryResult();
    }
}
