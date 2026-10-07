package io.akka.mcp.slack.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.mcp.slack.testsupport.FakeHostedSlackMcp;
import io.akka.mcp.slack.testsupport.FakeSlackApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway decides whether a tool needs the writer role from the {@code readOnlyHint} this
 * server advertises. A write tool advertised as read-only would skip the gateway's write gate
 * entirely, so the annotations are pinned here, together with the wiring of the one write tool.
 */
public class SlackMcpEndpointIntegrationTest extends TestKitSupport {

    private static final FakeSlackApi SLACK = FakeSlackApi.start();
    private static final FakeHostedSlackMcp HOSTED = FakeHostedSlackMcp.start();

    @AfterAll
    public static void stopFakeSlack() {
        SLACK.close();
        HOSTED.close();
    }

    // The endpoint only admits the gateway service, which the test client is not, and it talks to the
    // fake Slack instead of the real one.
    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAclDisabled()
                .withAdditionalConfig("slack.api-base-url = \"" + SLACK.url() + "\"\n"
                        + "slack.hosted-mcp-url = \"" + HOSTED.url() + "\"");
    }

    @BeforeEach
    public void resetFakeSlack() {
        SLACK.reset();
        HOSTED.reset();
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

    private static final String MARKER = "🤖 ";

    private List<FakeSlackApi.Request> postsToSlack() {
        return SLACK.requests().stream().filter(r -> r.path().equals("/chat.postMessage")).toList();
    }

    private static String resultText(JsonNode response) {
        return response.path("result").path("content").get(0).path("text").asText();
    }

    private static boolean isToolError(JsonNode response) {
        return response.path("result").path("isError").asBoolean(false);
    }

    @Test
    public void postMessage_isAdvertisedAsAWriteTool() throws Exception {
        var tool = toolsByName().get("slack_post_message");

        assertThat(tool).isNotNull();
        assertThat(tool.path("annotations").path("readOnlyHint").asBoolean(true)).isFalse();
        assertThat(tool.path("annotations").path("destructiveHint").asBoolean(true)).isFalse();
        assertThat(tool.path("inputSchema").path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("channel", "text");
        assertThat(tool.path("inputSchema").path("properties").path("omit_assistant_marker").path("type").asText())
                .isEqualTo("boolean");
    }

    @Test
    public void everyOtherTool_isAdvertisedAsReadOnly() throws Exception {
        var tools = toolsByName();

        assertThat(tools).containsKeys("slack_list_channels", "slack_search_messages");
        tools.forEach((name, tool) -> {
            if (!name.equals("slack_post_message") && !name.equals("slack_draft_message")) {
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

        assertThat(isToolError(response)).isFalse();
        var sent = postsToSlack().get(0);
        assertThat(sent.authorization()).isEqualTo("Bearer user-token");
        assertThat(sent.body().path("channel").asText()).isEqualTo("C123");
        assertThat(sent.body().path("text").asText()).isEqualTo(MARKER + "hello team");
        assertThat(sent.body().path("thread_ts").asText()).isEqualTo("1700000000.000200");
    }

    @Test
    public void postMessage_marksATopLevelPostToo() throws Exception {
        postMessage("user-token", Map.of("channel", "C123", "text", "hello team"));

        var sent = postsToSlack().get(0);
        assertThat(sent.body().has("thread_ts")).isFalse();
        assertThat(sent.body().path("text").asText()).isEqualTo(MARKER + "hello team");
    }

    @Test
    public void postMessage_canLeaveTheMarkerOff_onlyWhenAskedTo() throws Exception {
        postMessage("user-token", Map.of("channel", "C123", "text", "one", "omit_assistant_marker", true));
        postMessage("user-token", Map.of("channel", "C123", "text", "two", "omit_assistant_marker", false));
        postMessage("user-token", Map.of("channel", "C123", "text", "three"));

        assertThat(postsToSlack()).extracting(r -> r.body().path("text").asText())
                .containsExactly("one", MARKER + "two", MARKER + "three");
    }

    private String textPostedWith(Object omitAssistantMarker) throws Exception {
        SLACK.reset();
        var arguments = new HashMap<String, Object>(Map.of("channel", "C123", "text", "hello team"));
        arguments.put("omit_assistant_marker", omitAssistantMarker);
        postMessage("user-token", arguments);
        return postsToSlack().get(0).body().path("text").asText();
    }

    private static String describe(Object value) {
        return value.getClass().getSimpleName() + " \"" + value + "\"";
    }

    @Test
    public void omitAssistantMarker_acceptsTrueAsABooleanOrAsText() throws Exception {
        for (Object value : List.of(true, "true", "TRUE", "True")) {
            assertThat(textPostedWith(value)).as(describe(value)).isEqualTo("hello team");
        }
    }

    @Test
    public void omitAssistantMarker_anythingElseKeepsTheMarker() throws Exception {
        for (Object value : List.of(false, "false", "yes", "1", 1, "")) {
            assertThat(textPostedWith(value)).as(describe(value)).isEqualTo(MARKER + "hello team");
        }
        assertThat(textPostedWith(null)).isEqualTo(MARKER + "hello team");
    }

    @Test
    public void postMessage_toAChannelSharedOutsideAkka_isNeverPosted_andNeverMarked() throws Exception {
        var externalChannels = List.of(
                FakeSlackApi.channel("shared-with-acme", true, false),
                FakeSlackApi.channel("shared-with-acme", false, true),
                FakeSlackApi.channel("external-acme-support", false, false));
        for (var channel : externalChannels) {
            SLACK.reset();
            SLACK.replyingWith("/conversations.info", channel);

            var response = postMessage("user-token", Map.of("channel", "C123", "text", "hello customer"));

            assertThat(isToolError(response)).as(channel).isTrue();
            assertThat(postsToSlack()).as(channel).isEmpty();
            assertThat(resultText(response)).as(channel).contains("hello customer").doesNotContain(MARKER.trim());
            assertThat(SLACK.requests()).as(channel).singleElement()
                    .satisfies(r -> assertThat(r.authorization()).isEqualTo("Bearer user-token"));
        }
    }

    @Test
    public void postMessage_toAnExternalChannel_isNotPosted_whateverTheMarkerSetting() throws Exception {
        SLACK.replyingWith("/conversations.info", FakeSlackApi.channel("external-acme-support", false, false));

        var omitted = postMessage("user-token", Map.of("channel", "C123", "text", "hi", "omit_assistant_marker", true));
        var kept = postMessage("user-token", Map.of("channel", "C123", "text", "hi", "omit_assistant_marker", false));

        assertThat(isToolError(omitted)).isTrue();
        assertThat(isToolError(kept)).isTrue();
        assertThat(postsToSlack()).isEmpty();
    }

    @Test
    public void postMessage_toAnExternalChannelThread_isNotPostedEither() throws Exception {
        SLACK.replyingWith("/conversations.info", FakeSlackApi.channel("shared-with-acme", true, false));

        var response = postMessage("user-token",
                Map.of("channel", "C123", "text", "hi", "thread_ts", "1700000000.000200"));

        assertThat(isToolError(response)).isTrue();
        assertThat(postsToSlack()).isEmpty();
    }

    @Test
    public void postMessage_toAnInternalChannelWithExternalOnlyInTheMiddleOfItsName_isPosted() throws Exception {
        SLACK.replyingWith("/conversations.info", FakeSlackApi.channel("eng-external-notes", false, false));

        var response = postMessage("user-token", Map.of("channel", "C123", "text", "hello team"));

        assertThat(isToolError(response)).isFalse();
        assertThat(postsToSlack()).hasSize(1);
    }

    @Test
    public void postMessage_whenTheChannelCannotBeChecked_isNotPosted() throws Exception {
        SLACK.replyingWith("/conversations.info", "{\"ok\":false,\"error\":\"channel_not_found\"}");

        var response = postMessage("user-token", Map.of("channel", "C123", "text", "hello team"));

        assertThat(isToolError(response)).isTrue();
        assertThat(postsToSlack()).isEmpty();
    }

    @Test
    public void postMessage_withAMissingArgument_isAToolErrorAndNeverReachesSlack() throws Exception {
        var noText = postMessage("user-token", Map.of("channel", "C123"));
        var noChannel = postMessage("user-token", Map.of("text", "hello team"));

        assertThat(isToolError(noText)).isTrue();
        assertThat(isToolError(noChannel)).isTrue();
        assertThat(SLACK.requests()).isEmpty();
    }

    @Test
    public void postMessage_whenSlackRefusesIt_isAToolError() throws Exception {
        SLACK.replyingWith("/chat.postMessage", "{\"ok\":false,\"error\":\"channel_not_found\"}");

        var response = postMessage("user-token", Map.of("channel", "C0", "text", "hello team"));

        assertThat(isToolError(response)).isTrue();
    }

    private JsonNode draftMessage(String bearer, Map<String, Object> arguments) throws Exception {
        return rpc(bearer, Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "slack_draft_message", "arguments", arguments)));
    }

    @Test
    public void draftMessage_isAdvertisedAsAWriteTool() throws Exception {
        var tool = toolsByName().get("slack_draft_message");

        assertThat(tool).isNotNull();
        assertThat(tool.path("annotations").path("readOnlyHint").asBoolean(true)).isFalse();
        assertThat(tool.path("inputSchema").path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("channel", "text");
    }

    @Test
    public void draftMessage_withoutATokenIsRefused() throws Exception {
        var response = draftMessage(null, Map.of("channel", "C1", "text", "hi"));

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32001);
        assertThat(HOSTED.requests()).isEmpty();
    }

    @Test
    public void draftMessage_asksTheHostedServerToDraftAsTheCaller_andSendsNothing() throws Exception {
        var response = draftMessage("user-token",
                Map.of("channel", "C123", "text", "hello team", "thread_ts", "1700000000.000200"));

        assertThat(response.path("result").path("isError").asBoolean(true)).isFalse();
        assertThat(resultText(response)).contains("https://app.slack.com/client/T1/C123");
        var call = HOSTED.toolCall();
        assertThat(call.authorization()).isEqualTo("Bearer user-token");
        assertThat(call.sessionId()).isEqualTo(FakeHostedSlackMcp.SESSION_ID);
        assertThat(call.body().path("params").path("name").asText()).isEqualTo("slack_send_message_draft");
        var arguments = call.body().path("params").path("arguments");
        assertThat(arguments.path("channel_id").asText()).isEqualTo("C123");
        assertThat(arguments.path("message").asText()).isEqualTo("hello team");
        assertThat(arguments.path("thread_ts").asText()).isEqualTo("1700000000.000200");
        assertThat(SLACK.requests()).isEmpty();
    }

    @Test
    public void draftMessage_withoutAThread_isATopLevelDraft() throws Exception {
        draftMessage("user-token", Map.of("channel", "C123", "text", "hello team"));

        assertThat(HOSTED.toolCall().body().path("params").path("arguments").has("thread_ts")).isFalse();
    }

    @Test
    public void draftMessage_readsAnEventStreamAnswer() throws Exception {
        HOSTED.answeringAsAnEventStream();

        var response = draftMessage("user-token", Map.of("channel", "C123", "text", "hello team"));

        assertThat(response.path("result").path("isError").asBoolean(true)).isFalse();
        assertThat(resultText(response)).contains("https://app.slack.com/client/T1/C123");
    }

    @Test
    public void draftMessage_whenADraftAlreadyExists_saysWhatToDo() throws Exception {
        HOSTED.replyingToTheToolCallWith(FakeHostedSlackMcp.toolResult("draft_already_exists", true));

        var response = draftMessage("user-token", Map.of("channel", "C123", "text", "hello team"));

        assertThat(response.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(resultText(response)).contains("already exists").contains("send or delete");
    }

    @Test
    public void draftMessage_whenTheSlackAppHasNotEnabledMcp_tellsTheAdministrator() throws Exception {
        HOSTED.replyingToTheToolCallWith("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,"
                + "\"message\":\"App is not enabled for Slack MCP server access.\"}}");

        var response = draftMessage("user-token", Map.of("channel", "C123", "text", "hello team"));

        assertThat(response.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(resultText(response)).contains("administrator");
    }

    @Test
    public void draftMessage_whenSlackRefusesTheToken_asksTheUserToReconnect() throws Exception {
        HOSTED.answeringWithStatus(401);

        var response = draftMessage("user-token", Map.of("channel", "C123", "text", "hello team"));

        assertThat(response.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(resultText(response)).contains("reconnect");
    }

    @Test
    public void draftMessage_withAMissingArgument_isAToolErrorAndNeverReachesSlack() throws Exception {
        var noText = draftMessage("user-token", Map.of("channel", "C123"));
        var noChannel = draftMessage("user-token", Map.of("text", "hello team"));

        assertThat(noText.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(noChannel.path("result").path("isError").asBoolean(false)).isTrue();
        assertThat(HOSTED.requests()).isEmpty();
    }

    @Test
    public void draftMessage_toAnExternalChannel_isStillDrafted() throws Exception {
        SLACK.replyingWith("{\"ok\":true,\"channel\":{\"id\":\"C9\",\"name\":\"external-acme\",\"is_ext_shared\":true}}");

        var response = draftMessage("user-token", Map.of("channel", "C9", "text", "hello partner"));

        assertThat(response.path("result").path("isError").asBoolean(true)).isFalse();
        assertThat(HOSTED.toolCall().body().path("params").path("arguments").path("channel_id").asText()).isEqualTo("C9");
    }
}
