package io.akka.mcp.gateway.application;

import akka.javasdk.client.ComponentClient;
import io.akka.mcp.gateway.domain.McpConfig;

import java.util.List;

/** Writes what a client listed into the gateway's shared tool cache. */
public final class ToolRegistry {

    private ToolRegistry() {}

    /** A client whose tool list depends on who asks only ever adds to the cache, so one caller's list cannot shrink it for everyone. */
    public static void record(ComponentClient componentClient, RemoteMcpClient client, List<McpConfig.ToolMeta> tools) {
        var config = new McpConfig(client.getMcpId(), client.getMcpName(), tools);
        var registry = componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID);
        if (client.toolListDependsOnCaller()) {
            registry.method(McpRegistryEntity::registerAdding).invoke(config);
        } else {
            registry.method(McpRegistryEntity::register).invoke(config);
        }
    }
}
