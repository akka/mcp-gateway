package io.akka.mcp.gateway.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.consumer.Consumer;
import io.akka.mcp.gateway.domain.McpInteractionEvent;

@Component(id = "unique-interaction-actors-consumer")
@Consume.FromEventSourcedEntity(McpInteractionEntity.class)
public class UniqueInteractionActorsConsumer extends Consumer {

    private final ComponentClient componentClient;

    public UniqueInteractionActorsConsumer(ComponentClient componentClient) {
        this.componentClient = componentClient;
    }

    public Effect onEvent(McpInteractionEvent event) {
        if (event instanceof McpInteractionEvent.InteractionRecorded e) {
            componentClient.forKeyValueEntity(e.userId())
                    .method(UniqueInteractionUserEntity::upsert)
                    .invoke(e.userId());
            componentClient.forKeyValueEntity(e.mcpId())
                    .method(UniqueInteractionMcpEntity::upsert)
                    .invoke(e.mcpId());
        }
        return effects().done();
    }
}
