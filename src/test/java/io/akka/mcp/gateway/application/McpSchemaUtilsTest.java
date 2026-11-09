package io.akka.mcp.gateway.application;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpToolMetadataKeys;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class McpSchemaUtilsTest {

    private static ToolSpecification specWith(Map<String, Object> metadata) {
        return ToolSpecification.builder()
                .name("get_item")
                .description("Get an item")
                .parameters(JsonObjectSchema.builder().build())
                .metadata(metadata)
                .build();
    }

    @Test
    public void toolEntry_listsTheToolUnderTheGivenName() {
        var entry = McpSchemaUtils.toolEntry("TestToken_get_item", specWith(Map.of()));

        assertThat(entry.toolSpec()).containsEntry("name", "TestToken_get_item");
        assertThat(entry.meta().name()).isEqualTo("TestToken_get_item");
    }

    @Test
    public void toolEntry_aToolThatSaysItOnlyReads_isARead() {
        var entry = McpSchemaUtils.toolEntry("t", specWith(Map.of(McpToolMetadataKeys.READ_ONLY_HINT, true)));

        assertThat(entry.meta().isWrite()).isFalse();
    }

    @Test
    public void toolEntry_aToolThatSaysItDoesNotOnlyRead_isAWrite() {
        var entry = McpSchemaUtils.toolEntry("t", specWith(Map.of(McpToolMetadataKeys.READ_ONLY_HINT, false)));

        assertThat(entry.meta().isWrite()).isTrue();
    }

    @Test
    public void toolEntry_aToolThatSaysNothingAboutIt_isAWrite() {
        var entry = McpSchemaUtils.toolEntry("t", specWith(Map.of()));

        assertThat(entry.meta().isWrite()).isTrue();
    }

    @Test
    public void toolEntry_keepsTheAnnotationsTheToolDeclares_andLeavesOutTheOnesItDoesNot() {
        var entry = McpSchemaUtils.toolEntry("t", specWith(Map.of(
                McpToolMetadataKeys.READ_ONLY_HINT, true, McpToolMetadataKeys.TITLE, "Get an item")));

        assertThat(entry.toolSpec().get("annotations"))
                .isEqualTo(Map.of(McpToolMetadataKeys.READ_ONLY_HINT, true, McpToolMetadataKeys.TITLE, "Get an item"));
        assertThat(McpSchemaUtils.toolEntry("t", specWith(Map.of())).toolSpec()).doesNotContainKey("annotations");
    }
}
