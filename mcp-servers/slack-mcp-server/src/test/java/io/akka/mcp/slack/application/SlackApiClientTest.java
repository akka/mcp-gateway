package io.akka.mcp.slack.application;

import io.akka.mcp.slack.testsupport.FakeSlackApi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SlackApiClientTest {

    private static final String MARKER = "🤖 ";
    private static final FakeSlackApi SLACK = FakeSlackApi.start();

    @AfterAll
    public static void stopFakeSlack() {
        SLACK.close();
    }

    @BeforeEach
    public void resetFakeSlack() {
        SLACK.reset();
    }

    private SlackApiClient client() {
        return new SlackApiClient("xoxp-user-token", SLACK.url());
    }

    @Test
    public void postMessage_postsJsonToChatPostMessageAsTheUser() throws Exception {
        var reply = client().postMessage("C12345", "hello team", "1699999999.000200", true);

        var request = SLACK.onlyRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/chat.postMessage");
        assertThat(request.authorization()).isEqualTo("Bearer xoxp-user-token");
        assertThat(request.contentType()).startsWith("application/json");
        assertThat(request.body().path("channel").asText()).isEqualTo("C12345");
        assertThat(request.body().path("text").asText()).isEqualTo(MARKER + "hello team");
        assertThat(request.body().path("thread_ts").asText()).isEqualTo("1699999999.000200");
        assertThat(reply.path("ts").asText()).isEqualTo("1700000000.000100");
    }

    @Test
    public void postMessage_withoutAThread_postsATopLevelMessage() throws Exception {
        client().postMessage("C12345", "hello team", null, true);
        client().postMessage("C12345", "hello team", "  ", true);

        assertThat(SLACK.requests()).hasSize(2).allSatisfy(r -> assertThat(r.body().has("thread_ts")).isFalse());
    }

    @Test
    public void postMessage_marksTopLevelPostsAndThreadedRepliesAlike() throws Exception {
        client().postMessage("C12345", "hello team", null, true);
        client().postMessage("C12345", "hello team", "1699999999.000200", true);

        assertThat(SLACK.requests()).hasSize(2)
                .allSatisfy(r -> assertThat(r.body().path("text").asText()).isEqualTo(MARKER + "hello team"));
    }

    @Test
    public void postMessage_canLeaveTheMarkerOff() throws Exception {
        client().postMessage("C12345", "hello team", null, false);
        client().postMessage("C12345", "hello team", "1699999999.000200", false);

        assertThat(SLACK.requests()).hasSize(2)
                .allSatisfy(r -> assertThat(r.body().path("text").asText()).isEqualTo("hello team"));
    }

    @Test
    public void postMessage_whenTheTokenLacksChatWrite_reportsTheSlackErrorCodeWithGuidance() {
        SLACK.replyingWith("/chat.postMessage", "{\"ok\":false,\"error\":\"missing_scope\"}");

        assertThatThrownBy(() -> client().postMessage("C12345", "hi", null, true))
                .isInstanceOfSatisfying(SlackApiClient.SlackApiException.class, e -> {
                    assertThat(e.slackError()).isEqualTo("missing_scope");
                    assertThat(e.getMessage()).contains("cannot post messages", "reconnect");
                });
    }

    @Test
    public void postMessage_passesOtherSlackErrorsThroughWithoutGuidance() {
        SLACK.replyingWith("/chat.postMessage", "{\"ok\":false,\"error\":\"channel_not_found\"}");

        assertThatThrownBy(() -> client().postMessage("C0000", "hi", null, true))
                .isInstanceOfSatisfying(SlackApiClient.SlackApiException.class, e -> {
                    assertThat(e.slackError()).isEqualTo("channel_not_found");
                    assertThat(e.getMessage()).isEqualTo("Slack API error: channel_not_found");
                });
    }

    @Test
    public void updateMessage_postsJsonToChatUpdateAsTheUser() throws Exception {
        var reply = client().updateMessage("C12345", "1700000000.000100", "`akka.db.*` fixed", true);

        var request = SLACK.onlyRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/chat.update");
        assertThat(request.authorization()).isEqualTo("Bearer xoxp-user-token");
        assertThat(request.contentType()).startsWith("application/json");
        assertThat(request.body().path("channel").asText()).isEqualTo("C12345");
        assertThat(request.body().path("ts").asText()).isEqualTo("1700000000.000100");
        assertThat(request.body().path("text").asText()).isEqualTo(MARKER + "`akka.db.*` fixed");
        assertThat(reply.path("ts").asText()).isEqualTo("1700000000.000100");
    }

    @Test
    public void updateMessage_canLeaveTheMarkerOff() throws Exception {
        client().updateMessage("C12345", "1700000000.000100", "hello team", false);

        assertThat(SLACK.onlyRequest().body().path("text").asText()).isEqualTo("hello team");
    }

    @Test
    public void updateMessage_ofTextThatAlreadyCarriesTheMarker_marksItOnce() throws Exception {
        client().updateMessage("C12345", "1700000000.000100", MARKER + "hello again", true);
        client().updateMessage("C12345", "1700000000.000100", "🤖hello again", true);
        client().updateMessage("C12345", "1700000000.000100", MARKER + MARKER + "hello again", true);

        assertThat(SLACK.requests()).hasSize(3)
                .allSatisfy(r -> assertThat(r.body().path("text").asText()).isEqualTo(MARKER + "hello again"));
    }

    @Test
    public void postMessage_prefixesTheMarkerWithoutTouchingTheText() throws Exception {
        client().postMessage("C12345", MARKER + "hello team", null, true);

        assertThat(SLACK.onlyRequest().body().path("text").asText()).isEqualTo(MARKER + MARKER + "hello team");
    }

    @Test
    public void markerInTheMiddleOfTheText_isLeftAlone() throws Exception {
        client().updateMessage("C12345", "1700000000.000100", "deploy 🤖 bot", true);

        assertThat(SLACK.onlyRequest().body().path("text").asText()).isEqualTo(MARKER + "deploy 🤖 bot");
    }

    @Test
    public void updateMessage_whenTheTokenLacksChatWrite_reportsTheSlackErrorCodeWithGuidance() {
        SLACK.replyingWith("/chat.update", "{\"ok\":false,\"error\":\"missing_scope\"}");

        assertThatThrownBy(() -> client().updateMessage("C12345", "1700000000.000100", "hi", true))
                .isInstanceOfSatisfying(SlackApiClient.SlackApiException.class, e -> {
                    assertThat(e.slackError()).isEqualTo("missing_scope");
                    assertThat(e.getMessage()).contains("cannot edit messages", "reconnect")
                            .doesNotContain("post");
                });
    }

    @Test
    public void updateMessage_ofSomeoneElsesMessage_passesTheSlackErrorThroughAsIs() {
        SLACK.replyingWith("/chat.update", "{\"ok\":false,\"error\":\"cant_update_message\"}");

        assertThatThrownBy(() -> client().updateMessage("C12345", "1700000000.000100", "hi", true))
                .isInstanceOfSatisfying(SlackApiClient.SlackApiException.class, e -> {
                    assertThat(e.slackError()).isEqualTo("cant_update_message");
                    assertThat(e.getMessage()).isEqualTo("Slack API error: cant_update_message");
                });
    }

    @Test
    public void channelInfo_ofAnOrdinaryChannel_isNotExternal() throws Exception {
        var channel = client().channelInfo("C123");

        assertThat(channel.external()).isFalse();
        assertThat(channel.name()).isEqualTo("eng");
        var request = SLACK.onlyRequest();
        assertThat(request.path()).isEqualTo("/conversations.info");
        assertThat(request.authorization()).isEqualTo("Bearer xoxp-user-token");
    }

    @Test
    public void channelInfo_ofASlackConnectChannel_isExternal() throws Exception {
        SLACK.replyingWith("/conversations.info", FakeSlackApi.channel("shared-with-acme", true, false));

        assertThat(client().channelInfo("C123").external()).isTrue();
    }

    @Test
    public void channelInfo_ofAPendingSlackConnectChannel_isExternal() throws Exception {
        SLACK.replyingWith("/conversations.info", FakeSlackApi.channel("shared-with-acme", false, true));

        assertThat(client().channelInfo("C123").external()).isTrue();
    }

    @Test
    public void channelInfo_ofAChannelNamedWithTheExternalPrefix_isExternal() throws Exception {
        for (var name : new String[] {"external-acme-support", "external_acme", "External-Acme"}) {
            SLACK.replyingWith("/conversations.info", FakeSlackApi.channel(name, false, false));

            assertThat(client().channelInfo("C123").external()).as(name).isTrue();
        }
    }

    @Test
    public void channelInfo_onlyTheNamePrefixCounts() throws Exception {
        for (var name : new String[] {"eng-external-notes", "externalities", "external"}) {
            SLACK.replyingWith("/conversations.info", FakeSlackApi.channel(name, false, false));

            assertThat(client().channelInfo("C123").external()).as(name).isFalse();
        }
    }

    @Test
    public void channelInfo_whenSlackCannotSayWhatTheChannelIs_fails() {
        SLACK.replyingWith("/conversations.info", "{\"ok\":false,\"error\":\"channel_not_found\"}");

        assertThatThrownBy(() -> client().channelInfo("C0000"))
                .isInstanceOfSatisfying(SlackApiClient.SlackApiException.class,
                        e -> assertThat(e.slackError()).isEqualTo("channel_not_found"));
    }

    @Test
    public void reads_stillUseGetAgainstTheSameBase() throws Exception {
        client().userInfo("U123");

        var request = SLACK.onlyRequest();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo("/users.info");
    }
}
