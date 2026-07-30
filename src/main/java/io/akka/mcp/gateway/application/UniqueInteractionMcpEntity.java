package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;

@Component(id = "unique-interaction-mcp")
public class UniqueInteractionMcpEntity extends KeyValueEntity<UniqueInteractionMcpEntity.State> {

    public record State(String mcpId) {}

    public Effect<Done> upsert(String mcpId) {
        return effects().updateState(new State(mcpId)).thenReply(Done.getInstance());
    }
}
