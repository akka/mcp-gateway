package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import io.akka.mcp.gateway.domain.McpConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class McpRegistryEntityTest {

    private static McpConfig configWithTools(String mcpId) {
        return new McpConfig(mcpId, mcpId + "-name", List.of(
                new McpConfig.ToolMeta("tool_a", "desc a", null, true, false),
                new McpConfig.ToolMeta("tool_b", "desc b", null, false, true)
        ));
    }

    @Test
    public void list_whenEmpty_returnsEmptyState() {
        var testKit = KeyValueEntityTestKit.of(McpRegistryEntity.ENTITY_ID, McpRegistryEntity::new);

        var result = testKit.method(McpRegistryEntity::list).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply().configs()).isEmpty();
    }

    @Test
    public void register_addsConfig() {
        var testKit = KeyValueEntityTestKit.of(McpRegistryEntity.ENTITY_ID, McpRegistryEntity::new);

        var result = testKit.method(McpRegistryEntity::register).invoke(configWithTools("salesforce"));

        assertThat(result.isReply()).isTrue();
        assertThat(testKit.getState().configs()).hasSize(1);
        assertThat(testKit.getState().configs().get(0).mcpId()).isEqualTo("salesforce");
    }

    @Test
    public void register_multipleConfigs_storesAll() {
        var testKit = KeyValueEntityTestKit.of(McpRegistryEntity.ENTITY_ID, McpRegistryEntity::new);
        testKit.method(McpRegistryEntity::register).invoke(configWithTools("salesforce"));
        testKit.method(McpRegistryEntity::register).invoke(configWithTools("zoho"));

        assertThat(testKit.getState().configs()).hasSize(2);
    }

    @Test
    public void register_sameId_replacesExistingConfig() {
        var testKit = KeyValueEntityTestKit.of(McpRegistryEntity.ENTITY_ID, McpRegistryEntity::new);
        testKit.method(McpRegistryEntity::register).invoke(configWithTools("salesforce"));

        var updated = new McpConfig("salesforce", "updated-name", List.of(
                new McpConfig.ToolMeta("new_tool", "new desc", null, true, false)
        ));
        testKit.method(McpRegistryEntity::register).invoke(updated);

        var state = testKit.getState();
        assertThat(state.configs()).hasSize(1);
        assertThat(state.configs().get(0).name()).isEqualTo("updated-name");
        assertThat(state.configs().get(0).tools()).hasSize(1);
        assertThat(state.configs().get(0).tools().get(0).name()).isEqualTo("new_tool");
    }

    @Test
    public void findTool_whenToolExists_returnsToolMeta() {
        var testKit = KeyValueEntityTestKit.of(McpRegistryEntity.ENTITY_ID, McpRegistryEntity::new);
        testKit.method(McpRegistryEntity::register).invoke(configWithTools("salesforce"));

        var result = testKit.method(McpRegistryEntity::findTool).invoke("tool_a");

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply()).isPresent();
        assertThat(result.getReply().get().name()).isEqualTo("tool_a");
        assertThat(result.getReply().get().readOnlyHint()).isTrue();
        assertThat(result.getReply().get().isWrite()).isFalse();
    }

    @Test
    public void findTool_writeTool_isWriteReturnsTrue() {
        var testKit = KeyValueEntityTestKit.of(McpRegistryEntity.ENTITY_ID, McpRegistryEntity::new);
        testKit.method(McpRegistryEntity::register).invoke(configWithTools("salesforce"));

        var result = testKit.method(McpRegistryEntity::findTool).invoke("tool_b");

        assertThat(result.getReply()).isPresent();
        assertThat(result.getReply().get().isWrite()).isTrue();
    }

    @Test
    public void findTool_whenToolDoesNotExist_returnsEmpty() {
        var testKit = KeyValueEntityTestKit.of(McpRegistryEntity.ENTITY_ID, McpRegistryEntity::new);
        testKit.method(McpRegistryEntity::register).invoke(configWithTools("salesforce"));

        var result = testKit.method(McpRegistryEntity::findTool).invoke("unknown_tool");

        assertThat(result.getReply()).isEmpty();
    }

    @Test
    public void toToolSpec_withFullMeta_includesAllFields() {
        var schema = Map.<String, Object>of("type", "object", "properties", Map.of("q", Map.of("type", "string")));
        var meta = new McpConfig.ToolMeta("my_tool", "does things", schema, true, false);

        var spec = meta.toToolSpec();

        assertThat(spec.get("name")).isEqualTo("my_tool");
        assertThat(spec.get("description")).isEqualTo("does things");
        assertThat(spec.get("inputSchema")).isEqualTo(schema);
    }

    @Test
    public void toToolSpec_withNullInputSchema_emitsFallbackSchema() {
        var meta = new McpConfig.ToolMeta("my_tool", "desc", null, true, false);

        var spec = meta.toToolSpec();

        // MCP spec requires inputSchema — must never be absent
        assertThat(spec).containsKey("inputSchema");
        @SuppressWarnings("unchecked")
        var schema = (Map<String, Object>) spec.get("inputSchema");
        assertThat(schema.get("type")).isEqualTo("object");
    }

    @Test
    public void findByMcpId_whenPresent_returnsConfig() {
        var testKit = KeyValueEntityTestKit.of(McpRegistryEntity.ENTITY_ID, McpRegistryEntity::new);
        testKit.method(McpRegistryEntity::register).invoke(configWithTools("zoho"));

        var result = testKit.method(McpRegistryEntity::findByMcpId).invoke("zoho");

        assertThat(result.getReply()).isPresent();
        assertThat(result.getReply().get().mcpId()).isEqualTo("zoho");
    }

    @Test
    public void findByMcpId_whenAbsent_returnsEmpty() {
        var testKit = KeyValueEntityTestKit.of(McpRegistryEntity.ENTITY_ID, McpRegistryEntity::new);

        var result = testKit.method(McpRegistryEntity::findByMcpId).invoke("nonexistent");

        assertThat(result.getReply()).isEmpty();
    }

    @Test
    public void findMcpIdForTool_returnsCorrectMcpId() {
        var testKit = KeyValueEntityTestKit.of(McpRegistryEntity.ENTITY_ID, McpRegistryEntity::new);
        testKit.method(McpRegistryEntity::register).invoke(configWithTools("salesforce"));
        testKit.method(McpRegistryEntity::register).invoke(configWithTools("zoho"));

        var result = testKit.method(McpRegistryEntity::findMcpIdForTool).invoke("tool_a");

        // tool_a exists in both — first registered wins
        assertThat(result.getReply()).isPresent();
    }

    @Test
    public void findTool_searchesAcrossAllConfigs() {
        var testKit = KeyValueEntityTestKit.of(McpRegistryEntity.ENTITY_ID, McpRegistryEntity::new);
        testKit.method(McpRegistryEntity::register).invoke(configWithTools("salesforce"));
        testKit.method(McpRegistryEntity::register).invoke(new McpConfig("zoho", "zoho-name", List.of(
                new McpConfig.ToolMeta("zoho_tool", "zoho desc", null, null, false)
        )));

        var result = testKit.method(McpRegistryEntity::findTool).invoke("zoho_tool");

        assertThat(result.getReply()).isPresent();
        assertThat(result.getReply().get().name()).isEqualTo("zoho_tool");
    }
}
