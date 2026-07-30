package io.akka.mcp.gateway.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;

import java.util.List;

@Component(id = "unique-interaction-mcps-view")
public class UniqueInteractionMcpsView extends View {

    public record McpEntry(String mcpId) {}

    public record McpList(List<String> items) {}

    @Consume.FromKeyValueEntity(UniqueInteractionMcpEntity.class)
    public static class Updater extends TableUpdater<McpEntry> {
        public Effect<McpEntry> onUpdate(UniqueInteractionMcpEntity.State state) {
            return effects().updateRow(new McpEntry(state.mcpId()));
        }
    }

    @Query("SELECT mcpId AS items FROM unique_interaction_mcps_view ORDER BY mcpId ASC")
    public QueryEffect<McpList> getAll() {
        return queryResult();
    }
}
