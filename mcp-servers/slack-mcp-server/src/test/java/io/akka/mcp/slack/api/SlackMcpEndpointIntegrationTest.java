package io.akka.mcp.slack.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.mcp.slack.testsupport.FakeSlackApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway decides whether a tool needs the writer role from the {@code readOnlyHint} this
 * server advertises. A write tool advertised as read-only would skip the gateway's write gate
 * entirely, so the annotations are pinned here, together with the wiring of the one write tool.
 */
public class SlackMcpEndpointIntegrationTest extends TestKitSupport {

    private static final FakeSlackApi SLACK = FakeSlackApi.start();

    @AfterAll
    public static void stopFakeSlack() {
        SLACK.close();
    }

    // The endpoint only admits the gateway service, which the test client is not, and it talks to the
    // fake Slack instead of the real one.
    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAclDisabled()
                .withAdditionalConfig("slack.api-base-url = \"" + SLACK.url() + "\"");
    }

    @BeforeEach
    public void resetFakeSlack() {
        SLACK.reset();
    }

    private JsonNode rpc(String bearer, Map<String, Object> request) throws Exception {
        var builder = httpClient.POST("/mcp");
        if (bearer != null) builder = builder.addHeader("Authorization", "Bearer " + bearer);
        var response = builder.withRequestBody(request).responseBodyAs(String.class).invoke();
        return JsonSupport.getObjectMapper().readTree(response.body());
    }

    private Map<String, JsonNode> toolsByName() throws Exception {
        var tools = new HashMap<String, JsonNode>();
        rpc("test-token", Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .path("result").path("tools").forEach(t -> tools.put(t.path("name").asText(), t));
        return tools;
    }

    private JsonNode postMessage(String bearer, Map<String, Object> arguments) throws Exception {
        return rpc(bearer, Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "slack_post_message", "arguments", arguments)));
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
        var response = postMessage(null, Map.of("channel", "C1", "text", "hi"));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32001);
        assertThat(SLACK.requests()).isEmpty();
    }

    @Test
    public void postMessage_sendsTheArgumentsToSlackAsTheCaller() throws Exception {
        var response = postMessage("user-token",
                Map.of("channel", "C123", "text", "hello team", "thread_ts", "1700000000.000200"));

        assertThat(response.path("result").path("isError").asBoolean(true)).isFalse();
        var sent = SLACK.onlyRequest();
        assertThat(sent.path()).isEqualTo("/chat.postMessage");
        assertThat(sent.authorization()).isEqualTo("Bearer user-token");
        assertThat(sent.body().path("channel").asText()).isEqualTo("C123");
        assertThat(sent.body().path("text").asText()).isEqualTo("hello team");
        assertThat(sent.body().path("thread_ts").asText()).isEqualTo("1700000000.000200");
    }

    @Test
    public void postMessage_withoutAThread_isATopLevelMessage() throws Exception {
        postMessage("user-token", Map.of("channel", "C123", "text", "hello team"));

        assertThat(SLACK.onlyRequest().body().has("thread_ts")).isFalse();
    }

    @Test
    public void postMessage_withAMissingArgument_isAToolErrorAndNeverReachesSlack() throws Exception {
        var noText = postMessage("user-token", Map.of("channel", "C123"));
        var noChannel = postMessage("user-token", Map.of("text", "hello team"));

        assertThat(noText.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(noChannel.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(SLACK.requests()).isEmpty();
    }

    @Test
    public void postMessage_whenSlackRefusesIt_isAToolError() throws Exception {
        SLACK.replyingWith("{\"ok\":false,\"error\":\"channel_not_found\"}");

        var response = postMessage("user-token", Map.of("channel", "C0", "text", "hello team"));

        assertThat(response.path("result").path("isError").asBoolean(false)).isTrue();
    }
}
