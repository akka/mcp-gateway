package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;

@Component(id = "unique-interaction-user")
public class UniqueInteractionUserEntity extends KeyValueEntity<UniqueInteractionUserEntity.State> {

    public record State(String userId) {}

    public Effect<Done> upsert(String userId) {
        return effects().updateState(new State(userId)).thenReply(Done.getInstance());
    }
}
