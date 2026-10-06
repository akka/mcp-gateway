package io.akka.mcp.slack.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway decides whether a tool needs the writer role from the {@code readOnlyHint} this
 * server advertises. A write tool advertised as read-only would skip the gateway's write gate
 * entirely, so the annotations are pinned here.
 */
public class SlackMcpEndpointIntegrationTest extends TestKitSupport {

    // The endpoint only admits the gateway service; the test client is not that service.
    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAclDisabled();
    }

    private Map<String, JsonNode> toolsByName() throws Exception {
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer test-token")
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .responseBodyAs(String.class)
                .invoke();
        var tools = new HashMap<String, JsonNode>();
        JsonSupport.getObjectMapper().readTree(response.body()).path("result").path("tools")
                .forEach(t -> tools.put(t.path("name").asText(), t));
        return tools;
    }

    @Test
    public void postMessage_isAdvertisedAsAWriteTool() throws Exception {
        var tool = toolsByName().get("slack_post_message");

        assertThat(tool).isNotNull();
        assertThat(tool.path("annotations").path("readOnlyHint").asBoolean(true)).isFalse();
        assertThat(tool.path("annotations").path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(tool.path("inputSchema").path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("channel", "text");
    }

    @Test
    public void everyOtherTool_isAdvertisedAsReadOnly() throws Exception {
        var tools = toolsByName();

        assertThat(tools).containsKeys("slack_list_channels", "slack_search_messages");
        tools.forEach((name, tool) -> {
            if (!name.equals("slack_post_message")) {
                assertThat(tool.path("annotations").path("readOnlyHint").asBoolean(false))
                        .as("%s should be read-only", name).isTrue();
            }
        });
    }

    @Test
    public void postMessage_withoutATokenIsRefused() throws Exception {
        var response = httpClient.POST("/mcp")
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                        "params", Map.of("name", "slack_post_message",
                                "arguments", Map.of("channel", "C1", "text", "hi"))))
                .responseBodyAs(String.class)
                .invoke();

        var error = JsonSupport.getObjectMapper().readTree(response.body()).path("error");
        assertThat(error.path("code").asInt()).isEqualTo(-32001);
    }
}
