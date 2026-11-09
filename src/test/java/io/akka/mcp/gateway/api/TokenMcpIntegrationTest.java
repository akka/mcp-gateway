package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.mcp.gateway.application.TokenConnectionEvent;
import io.akka.mcp.gateway.application.McpInteractionsByUserView;
import io.akka.mcp.gateway.application.SlackMcpClient;
import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.testsupport.FakeMcpServer;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.AdvertisedTool;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.ReceivedCall;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import io.akka.mcp.gateway.testsupport.LogCapture;
import io.akka.mcp.gateway.testsupport.TokenMcpFixtures;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The token connector forwards each user's own pasted token to the upstream, checks it with the upstream before
 * keeping it, never hands it back, and by default keeps no arguments or results of the calls in the gateway's history or
 * logs. A fake upstream stands in for the real one and refuses any request whose Bearer token it was not told to accept.
 */
public class TokenMcpIntegrationTest extends TestKitSupport {

    static final String READER_GROUP = "mcp-gateway-reader";
    static final String APP_ID = "token-service-app-id-under-test";
    private static final String WRITER_GROUP = "mcp-gateway-writer";
    static final String ADMIN_GROUP = "mcp-gateway-admin";
    private static final String GATEWAY_ORIGIN = "http://localhost:9000";
    private static final String MCP_ID = TokenMcpFixtures.ID;
    private static final String SLACK_MCP_ID = SlackMcpClient.MCP_ID;
    private static final String TOOL_PREFIX = TokenMcpFixtures.TOOL_PREFIX;
    private static final String GET_ITEM = "TestToken_get_item";
    private static final String UPDATE_ITEM = "TestToken_update_item";
    private static final String UPSTREAM_GET_ITEM = "get_item";
    private static final String UPSTREAM_UPDATE_ITEM = "update_item";

    private static final FakeMcpServer UPSTREAM = FakeMcpServer.start().advertising(
            new AdvertisedTool(UPSTREAM_GET_ITEM, true),
            new AdvertisedTool(UPSTREAM_UPDATE_ITEM, false));
    private static final FakeMcpServer SLACK = FakeMcpServer.start().advertising(
            new AdvertisedTool("slack_search_messages", true));
    private static final FakeMcpServer ANOTHER_HOST = FakeMcpServer.start();

    @AfterAll
    public static void stopFakeUpstreams() {
        UPSTREAM.close();
        SLACK.close();
        ANOTHER_HOST.close();
    }

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                %s
                slack.mcp-url = "%s"
                mcp.base-url = "%s"
                okta.groups.reader = "%s"
                okta.groups.writer = "%s"
                okta.groups.admin = "%s"
                """.formatted(TokenMcpFixtures.setting(UPSTREAM.url(), APP_ID), SLACK.url(), GATEWAY_ORIGIN, READER_GROUP, WRITER_GROUP, ADMIN_GROUP));
    }

    @BeforeEach
    public void freshUpstreamAndReadOnlyPolicy() {
        UPSTREAM.forgetCalls();
        UPSTREAM.respondingNormally();
        UPSTREAM.refusingEveryToken();
        GatewayFixtures.enableWritesOn(componentClient, List.of());
    }

    private record SignedIn(String email, String browserSession, String mcpToken) {}

    private record Reply(int status, String body) {}

    private SignedIn signIn() {
        return signIn(List.of(new UserSession.App(APP_ID, TokenMcpFixtures.NAME)));
    }

    private SignedIn signIn(List<UserSession.App> apps) {
        var email = "token-user-" + UUID.randomUUID() + "@lightbend.com";
        return new SignedIn(email,
                GatewayFixtures.browserSession(componentClient, email, List.of(), apps),
                GatewayFixtures.mcpToken(componentClient, email, List.of(READER_GROUP, WRITER_GROUP), apps));
    }

    private static String newUpstreamToken() {
        return "upstreamtoken-" + UUID.randomUUID();
    }

    private Reply connect(SignedIn user, String upstreamToken) {
        return connectFrom(GATEWAY_ORIGIN, user, upstreamToken);
    }

    private Reply connectFrom(String origin, SignedIn user, String upstreamToken) {
        var response = httpClient.POST(TokenMcpFixtures.path("connect"))
                .addHeader("Cookie", "SESSION=" + user.browserSession())
                .addHeader("Origin", origin)
                .withRequestBody(new TokenMcpEndpoint.ConnectRequest(upstreamToken))
                .invoke();
        return new Reply(response.status().intValue(), response.body().utf8String());
    }

    private Reply get(String path, SignedIn user) {
        var response = httpClient.GET(path).addHeader("Cookie", "SESSION=" + user.browserSession()).invoke();
        return new Reply(response.status().intValue(), response.body().utf8String());
    }

    private Reply disconnect(SignedIn user) {
        return disconnectFrom(GATEWAY_ORIGIN, user);
    }

    private Reply disconnectFrom(String origin, SignedIn user) {
        var response = httpClient.POST(TokenMcpFixtures.path("disconnect"))
                .addHeader("Cookie", "SESSION=" + user.browserSession())
                .addHeader("Origin", origin)
                .invoke();
        return new Reply(response.status().intValue(), response.body().utf8String());
    }

    private boolean connected(SignedIn user) throws Exception {
        return parse(get(TokenMcpFixtures.path("status"), user).body()).path("connected").asBoolean();
    }

    private SignedIn connectedUser(String upstreamToken) {
        var user = signIn();
        assertThat(connect(user, upstreamToken).status()).as("connecting with an accepted token").isEqualTo(200);
        return user;
    }

    private JsonNode rpc(SignedIn user, Map<String, Object> request) throws Exception {
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + user.mcpToken())
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();
        return parse(response.body());
    }

    private JsonNode callTool(SignedIn user, String toolName, Map<String, Object> arguments) throws Exception {
        return rpc(user, Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", toolName, "arguments", arguments)));
    }

    private List<String> listedTools(SignedIn user) throws Exception {
        var names = new ArrayList<String>();
        rpc(user, Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
        return names;
    }

    private static boolean isToolError(JsonNode response) {
        return response.path("result").path("isError").asBoolean(false);
    }

    private static String toolText(JsonNode response) {
        return response.path("result").path("content").path(0).path("text").asText();
    }

    private List<McpInteractionsByUserView.McpInteractionEntry> awaitInteractionsWith(SignedIn user, String mcpId, String... directions) {
        var found = new ArrayList<McpInteractionsByUserView.McpInteractionEntry>();
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var entries = componentClient.forView()
                    .method(McpInteractionsByUserView::getByUser)
                    .invoke(new McpInteractionsByUserView.UserPageRequest(user.email(), 0, 100))
                    .interactions().stream().filter(e -> e.mcpId().equals(mcpId)).toList();
            assertThat(entries).extracting(McpInteractionsByUserView.McpInteractionEntry::direction).contains(directions);
            found.clear();
            found.addAll(entries);
        });
        return found;
    }

    private static List<String> upstreamTools(List<String> tools) {
        return tools.stream().filter(name -> name.startsWith(TOOL_PREFIX)).sorted().toList();
    }

    @Test
    public void connectingWithATokenTheUpstreamAccepts_keepsIt_andNoRouteReturnsIt() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = signIn();

        var reply = connect(user, token);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(parse(reply.body()).path("ok").asBoolean()).isTrue();
        assertThat(reply.body()).contains(GET_ITEM).doesNotContain(token);
        var status = get(TokenMcpFixtures.path("status"), user);
        assertThat(parse(status.body()).path("connected").asBoolean()).isTrue();
        assertThat(status.body()).doesNotContain(token);
        var test = get(TokenMcpFixtures.path("test"), user);
        assertThat(parse(test.body()).path("ok").asBoolean()).isTrue();
        assertThat(test.body()).doesNotContain(token);
    }

    @Test
    public void connectingWithATokenTheUpstreamRefuses_keepsNothing() throws Exception {
        var user = signIn();

        var reply = connect(user, newUpstreamToken());

        assertThat(reply.status()).isEqualTo(400);
        assertThat(parse(reply.body()).path("ok").asBoolean()).isFalse();
        assertThat(connected(user)).isFalse();
    }

    @Test
    public void aRefusedToken_leavesAnExistingConnectionIntact() throws Exception {
        var good = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(good);
        var user = connectedUser(good);

        var reply = connect(user, newUpstreamToken());

        assertThat(reply.status()).isEqualTo(400);
        assertThat(connected(user)).isTrue();
        UPSTREAM.forgetCalls();
        assertThat(isToolError(callTool(user, GET_ITEM, Map.of()))).isFalse();
        assertThat(UPSTREAM.callsTo(UPSTREAM_GET_ITEM)).extracting(ReceivedCall::authorization).containsExactly("Bearer " + good);
    }

    @Test
    public void connectingAgain_replacesTheToken() throws Exception {
        var first = newUpstreamToken();
        var second = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(first, second);
        var user = connectedUser(first);

        assertThat(connect(user, second).status()).isEqualTo(200);

        UPSTREAM.forgetCalls();
        callTool(user, GET_ITEM, Map.of());
        assertThat(UPSTREAM.callsTo(UPSTREAM_GET_ITEM)).extracting(ReceivedCall::authorization).containsExactly("Bearer " + second);
    }

    @Test
    public void connectingWithoutASession_isRefused_andTheUpstreamIsNotAsked() {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var before = UPSTREAM.requestsReceived();

        var response = httpClient.POST(TokenMcpFixtures.path("connect"))
                .withRequestBody(new TokenMcpEndpoint.ConnectRequest(token))
                .invoke();

        assertThat(response.status().intValue()).isEqualTo(401);
        assertThat(UPSTREAM.requestsReceived()).isEqualTo(before);
    }

    @Test
    public void connectingWithoutTheOktaApp_isForbidden_andTheUpstreamIsNotAsked() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = signIn(List.of());
        var before = UPSTREAM.requestsReceived();

        var reply = connect(user, token);

        assertThat(reply.status()).isEqualTo(403);
        assertThat(UPSTREAM.requestsReceived()).isEqualTo(before);
        awaitInteractionsWith(user, MCP_ID, TokenConnectionEvent.CONNECT_BLOCKED.direction());
    }

    @Test
    public void testingWithoutTheOktaApp_isForbidden() {
        var user = signIn(List.of());

        assertThat(get(TokenMcpFixtures.path("test"), user).status()).isEqualTo(403);
    }

    @Test
    public void aMalformedToken_isRefused_andTheUpstreamIsNotAsked() throws Exception {
        var user = signIn();
        var before = UPSTREAM.requestsReceived();

        var withInjectedHeader = connect(user, "upstreamtoken_abc\r\nX-Injected: 1");
        var withSpace = connect(user, "upstreamtoken abc");
        var empty = connect(user, "   ");

        assertThat(List.of(withInjectedHeader.status(), withSpace.status(), empty.status())).containsOnly(400);
        assertThat(UPSTREAM.requestsReceived()).isEqualTo(before);
        assertThat(connected(user)).isFalse();
    }

    @Test
    public void aTokenPastedWithWhitespaceAndABearerPrefix_isCleanedBeforeUse() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = signIn();

        var reply = connect(user, "  Bearer " + token + "\n");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void whenTheUpstreamFailsWhileConnecting_thePersonIsToldWithoutTheUpstreamsWords_andNothingIsKept() throws Exception {
        var user = signIn();

        UPSTREAM.respondingWithStatus(503);
        var unavailable = connect(user, newUpstreamToken());
        UPSTREAM.respondingWithStatus(403);
        var forbidden = connect(user, newUpstreamToken());

        assertThat(unavailable.status()).isEqualTo(502);
        assertThat(unavailable.body()).contains("could not be reached").doesNotContain(FakeMcpServer.FAILURE_BODY);
        assertThat(forbidden.status()).isEqualTo(502);
        assertThat(forbidden.body()).contains("could not be checked").doesNotContain(FakeMcpServer.FAILURE_BODY);
        assertThat(connected(user)).isFalse();
    }

    @Test
    public void testingAConnection_neverDisconnects_andReportsTheFailureInPlainTerms() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = connectedUser(token);
        UPSTREAM.refusingEveryToken();

        var test = get(TokenMcpFixtures.path("test"), user);

        assertThat(parse(test.body()).path("ok").asBoolean()).isFalse();
        assertThat(test.body()).contains("did not accept that token").doesNotContain("401").doesNotContain("Unexpected");
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void aCallForwardsEachUsersOwnTokenAsBearer_underTheUpstreamsOwnToolName() throws Exception {
        var tokenA = newUpstreamToken();
        var tokenB = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(tokenA, tokenB);
        var userA = connectedUser(tokenA);
        var userB = connectedUser(tokenB);
        UPSTREAM.forgetCalls();

        var first = callTool(userA, GET_ITEM, Map.of("id", "1"));
        var second = callTool(userB, GET_ITEM, Map.of("id", "2"));

        assertThat(isToolError(first)).isFalse();
        assertThat(isToolError(second)).isFalse();
        assertThat(UPSTREAM.callsTo(UPSTREAM_GET_ITEM)).extracting(ReceivedCall::authorization)
                .containsExactly("Bearer " + tokenA, "Bearer " + tokenB);
        assertThat(UPSTREAM.callsTo(GET_ITEM)).isEmpty();
    }

    @Test
    public void aCallBeforeConnecting_saysTheConnectorIsNotConnected_andNothingReachesTheUpstream() throws Exception {
        var user = signIn();

        var response = callTool(user, GET_ITEM, Map.of("id", "1"));

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains(TokenMcpFixtures.NAME + " is not connected");
        assertThat(UPSTREAM.callsTo(UPSTREAM_GET_ITEM)).isEmpty();
    }

    @Test
    public void whenTheUpstreamRefusesTheStoredToken_theUserIsDisconnected_toldToReconnect_andItIsRecorded() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = connectedUser(token);
        UPSTREAM.refusingEveryToken();

        var response = callTool(user, GET_ITEM, Map.of("id", "1"));

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains("did not accept your token").contains("connect again");
        assertThat(connected(user)).isFalse();
        assertThat(toolText(callTool(user, GET_ITEM, Map.of("id", "1"))))
                .contains(TokenMcpFixtures.NAME + " is not connected");
        awaitInteractionsWith(user, MCP_ID, TokenConnectionEvent.CONNECT_SUCCESS.direction(), TokenConnectionEvent.DISCONNECT.direction());
    }

    @Test
    public void anUpstreamThatRefusesOnlyTheCall_disconnectsTheUser_andSaysNothingOfTheStatus() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = connectedUser(token);
        UPSTREAM.respondingToCallsWithStatus(401);

        var response = callTool(user, GET_ITEM, Map.of());

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains("did not accept your token")
                .doesNotContain("Unexpected").doesNotContain("401").doesNotContain(FakeMcpServer.FAILURE_BODY);
        assertThat(connected(user)).isFalse();
    }

    @Test
    public void anUpstreamThatFailsOnlyTheCall_keepsTheToken_andSaysItIsUnavailable() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = connectedUser(token);

        UPSTREAM.respondingToCallsWithStatus(503);
        var unavailable = callTool(user, GET_ITEM, Map.of());
        UPSTREAM.respondingToCallsWithStatus(403);
        var refused = callTool(user, GET_ITEM, Map.of());

        assertThat(toolText(unavailable)).contains("temporarily unavailable")
                .doesNotContain("Unexpected").doesNotContain(FakeMcpServer.FAILURE_BODY);
        assertThat(toolText(refused)).contains("request failed")
                .doesNotContain("Unexpected").doesNotContain(FakeMcpServer.FAILURE_BODY);
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void toolsList_doesNotAskTheUpstreamOfAUserWhoseToolsAreCached() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = connectedUser(token);
        var before = UPSTREAM.requestsReceived();

        var tools = upstreamTools(listedTools(user));

        assertThat(tools).isNotEmpty();
        assertThat(UPSTREAM.requestsReceived()).isEqualTo(before);
    }

    @Test
    public void whenTheUpstreamIsDown_theTokenIsKept_andTheCallWorksAgainOnceItIsBack() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = connectedUser(token);

        UPSTREAM.respondingWithStatus(503);
        var whileDown = callTool(user, GET_ITEM, Map.of());
        UPSTREAM.respondingWithStatus(429);
        var whileThrottled = callTool(user, GET_ITEM, Map.of());
        UPSTREAM.respondingNormally();
        var afterwards = callTool(user, GET_ITEM, Map.of());

        assertThat(toolText(whileDown)).contains("temporarily unavailable");
        assertThat(toolText(whileThrottled)).contains("temporarily unavailable");
        assertThat(isToolError(afterwards)).isFalse();
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void whenTheUpstreamRefusesTheCallForAnotherReason_theTokenIsKept() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = connectedUser(token);

        UPSTREAM.respondingWithStatus(403);
        var response = callTool(user, GET_ITEM, Map.of());

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains("request failed");
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void disconnecting_forgetsTheToken_recordsIt_andLaterCallsAreNotConnected() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = connectedUser(token);

        assertThat(disconnect(user).status()).isEqualTo(200);

        assertThat(connected(user)).isFalse();
        assertThat(toolText(callTool(user, GET_ITEM, Map.of()))).contains(TokenMcpFixtures.NAME + " is not connected");
        awaitInteractionsWith(user, MCP_ID, TokenConnectionEvent.CONNECT_ATTEMPT.direction(),
                TokenConnectionEvent.CONNECT_SUCCESS.direction(), TokenConnectionEvent.DISCONNECT.direction());
    }

    @Test
    public void aFailedConnect_isRecordedWithoutTheToken() throws Exception {
        var user = signIn();
        var token = newUpstreamToken();

        connect(user, token);

        var recorded = awaitInteractionsWith(user, MCP_ID, TokenConnectionEvent.CONNECT_ATTEMPT.direction(), TokenConnectionEvent.CONNECT_FAILED.direction());
        assertThat(recorded).anySatisfy(entry -> assertThat(entry.output()).contains("TOKEN_REJECTED"));
        assertThat(recorded).allSatisfy(entry -> {
            assertThat(entry.params()).doesNotContain(token);
            assertThat(entry.output().orElse("")).doesNotContain(token);
        });
    }

    @Test
    public void disconnectingWithoutASession_isRefused() {
        var response = httpClient.POST(TokenMcpFixtures.path("disconnect")).invoke();

        assertThat(response.status().intValue()).isEqualTo(401);
    }

    @Test
    public void byDefault_theGatewayKeepsNoArgumentsOrResultsOfTokenConnectorCalls() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = connectedUser(token);

        var response = callTool(user, GET_ITEM, Map.of("id", "REQUEST-ARGUMENT-123"));

        assertThat(isToolError(response)).isFalse();
        assertThat(toolText(response)).contains(FakeMcpServer.HANDLED_PREFIX + UPSTREAM_GET_ITEM);
        var recorded = awaitInteractionsWith(user, MCP_ID, "req", "resp");
        assertThat(recorded).allSatisfy(entry -> {
            assertThat(entry.params()).doesNotContain("REQUEST-ARGUMENT-123");
            assertThat(entry.output().orElse("")).doesNotContain(FakeMcpServer.HANDLED_PREFIX.trim());
        });
        assertThat(recorded).anySatisfy(entry -> assertThat(entry.params()).contains(AkkaMcpGateway.NOT_KEPT));
        assertThat(recorded).anySatisfy(entry -> assertThat(entry.output()).contains(AkkaMcpGateway.NOT_KEPT));
    }

    /** Control: a built-in system does record its arguments and results, so the checks above would notice a connector that kept them. */
    @Test
    public void otherConnectorsKeepTheirArgumentsAndResults() throws Exception {
        var user = signIn();
        GatewayFixtures.connectSlack(componentClient, user.email());
        listedTools(user);

        callTool(user, "slack_search_messages", Map.of("query", "SLACK-ARGUMENT-456"));

        var recorded = awaitInteractionsWith(user, SLACK_MCP_ID, "req", "resp");
        assertThat(recorded).anySatisfy(entry -> assertThat(entry.params()).contains("SLACK-ARGUMENT-456"));
        assertThat(recorded).anySatisfy(entry -> assertThat(entry.output()).contains(FakeMcpServer.HANDLED_PREFIX + "slack_search_messages"));
    }

    private SignedIn connectedUserWithErrorTools(String token) {
        UPSTREAM.acceptingOnlyBearer(token);
        UPSTREAM.advertisingTo(token,
                new AdvertisedTool(UPSTREAM_GET_ITEM, true),
                new AdvertisedTool(FakeMcpServer.ERROR_RESULT_TOOL, true),
                new AdvertisedTool(FakeMcpServer.PROTOCOL_ERROR_TOOL, true));
        return connectedUser(token);
    }

    @Test
    public void callBodiesResultsAndUpstreamErrorsAreNeverLogged_forAConnectorThatKeepsNoContent() throws Throwable {
        var token = newUpstreamToken();
        var user = signIn();
        var malformedBody = "{\"params\":{\"arguments\":{\"id\":\"MALFORMED-ARGUMENT-321\"}} oops".getBytes(StandardCharsets.UTF_8);
        var malformedConnect = "{\"token\":\"LEAKED-TOKEN-555\" oops".getBytes(StandardCharsets.UTF_8);

        var logged = LogCapture.during(() -> {
            UPSTREAM.acceptingOnlyBearer(token);
            UPSTREAM.advertisingTo(token,
                    new AdvertisedTool(UPSTREAM_GET_ITEM, true),
                    new AdvertisedTool(FakeMcpServer.ERROR_RESULT_TOOL, true),
                    new AdvertisedTool(FakeMcpServer.PROTOCOL_ERROR_TOOL, true));
            assertThat(connect(user, token).status()).isEqualTo(200);
            callTool(user, GET_ITEM, Map.of("id", "LOGGED-ARGUMENT-789"));
            callTool(user, TOOL_PREFIX + FakeMcpServer.ERROR_RESULT_TOOL, Map.of());
            callTool(user, TOOL_PREFIX + FakeMcpServer.PROTOCOL_ERROR_TOOL, Map.of());
            UPSTREAM.respondingWithStatus(503);
            callTool(user, GET_ITEM, Map.of());
            listedTools(user);
            UPSTREAM.respondingNormally();
            httpClient.POST("/mcp")
                    .addHeader("Authorization", "Bearer " + user.mcpToken())
                    .withRequestBody(ContentTypes.APPLICATION_JSON, malformedBody)
                    .invoke();
            httpClient.POST(TokenMcpFixtures.path("connect"))
                    .addHeader("Cookie", "SESSION=" + user.browserSession())
                    .withRequestBody(ContentTypes.APPLICATION_JSON, malformedConnect)
                    .invoke();
        });

        assertThat(logged).noneMatch(line -> line.contains("LOGGED-ARGUMENT-789")
                || line.contains("MALFORMED-ARGUMENT-321")
                || line.contains("LEAKED-TOKEN-555")
                || line.contains(FakeMcpServer.HANDLED_PREFIX + UPSTREAM_GET_ITEM)
                || line.contains(FakeMcpServer.ERROR_RESULT_TEXT)
                || line.contains(FakeMcpServer.PROTOCOL_ERROR_TEXT)
                || line.contains(token));
    }

    @Test
    public void aToolErrorFromTheUpstream_reachesTheCaller_butIsNotKeptInTheHistory() throws Exception {
        var user = connectedUserWithErrorTools(newUpstreamToken());

        var response = callTool(user, TOOL_PREFIX + FakeMcpServer.ERROR_RESULT_TOOL, Map.of());

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains(FakeMcpServer.ERROR_RESULT_TEXT);
        assertThat(awaitInteractionsWith(user, MCP_ID, "req", "resp")).allSatisfy(entry -> {
            assertThat(entry.params()).doesNotContain(FakeMcpServer.ERROR_RESULT_TEXT);
            assertThat(entry.output().orElse("")).doesNotContain(FakeMcpServer.ERROR_RESULT_TEXT);
        });
    }

    @Test
    public void aProtocolErrorFromTheUpstream_reachesTheCaller_butIsNotKeptInTheHistory() throws Exception {
        var user = connectedUserWithErrorTools(newUpstreamToken());

        var response = callTool(user, TOOL_PREFIX + FakeMcpServer.PROTOCOL_ERROR_TOOL, Map.of());

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains(FakeMcpServer.PROTOCOL_ERROR_TEXT);
        assertThat(awaitInteractionsWith(user, MCP_ID, "req", "resp")).allSatisfy(entry -> {
            assertThat(entry.params()).doesNotContain(FakeMcpServer.PROTOCOL_ERROR_TEXT);
            assertThat(entry.output().orElse("")).doesNotContain(FakeMcpServer.PROTOCOL_ERROR_TEXT);
        });
    }

    @Test
    public void connectingWithoutATokenField_isRefused() {
        var user = signIn();

        var response = httpClient.POST(TokenMcpFixtures.path("connect"))
                .addHeader("Cookie", "SESSION=" + user.browserSession())
                .withRequestBody(ContentTypes.APPLICATION_JSON, "{}".getBytes(StandardCharsets.UTF_8))
                .invoke();

        assertThat(response.status().intValue()).isEqualTo(400);
    }

    @Test
    public void anOriginIsComparedByItsSchemeHostAndPort_notByItsSpelling() {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = signIn();

        assertThat(connectFrom("http://localhost:9001", user, token).status()).isEqualTo(403);
        assertThat(connectFrom("not an origin", user, token).status()).isEqualTo(403);
        assertThat(connectFrom("HTTP://LOCALHOST:9000", user, token).status()).isEqualTo(200);
    }

    @Test
    public void everyUserSeesTheUpstreamTools_onceAnyoneHasConnected() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        connectedUser(token);
        var neverConnected = signIn();

        assertThat(listedTools(neverConnected)).contains(GET_ITEM, UPDATE_ITEM);
    }

    @Test
    public void everyoneIsListedTheWholeUpstreamToolList_whateverTheirTokenSees() throws Exception {
        var fullToken = newUpstreamToken();
        var restrictedToken = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(fullToken, restrictedToken);
        UPSTREAM.advertisingTo(fullToken,
                new AdvertisedTool(UPSTREAM_GET_ITEM, true),
                new AdvertisedTool(UPSTREAM_UPDATE_ITEM, false),
                new AdvertisedTool("only_on_the_full_list", true));
        UPSTREAM.advertisingTo(restrictedToken, new AdvertisedTool(UPSTREAM_GET_ITEM, true));
        connectedUser(fullToken);

        var restricted = connectedUser(restrictedToken);
        var bystander = signIn();

        assertThat(listedTools(restricted)).contains("TestToken_only_on_the_full_list", UPDATE_ITEM);
        assertThat(listedTools(bystander)).contains("TestToken_only_on_the_full_list", UPDATE_ITEM);
    }

    @Test
    public void aUsersToolList_doesNotChange_whenTheyConnectOrDisconnect() throws Exception {
        var warmingToken = newUpstreamToken();
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(warmingToken, token);
        connectedUser(warmingToken);
        var user = signIn();

        var before = upstreamTools(listedTools(user));
        assertThat(connect(user, token).status()).isEqualTo(200);
        var whileConnected = upstreamTools(listedTools(user));
        disconnect(user);
        var after = upstreamTools(listedTools(user));

        assertThat(before).isNotEmpty();
        assertThat(whileConnected).isEqualTo(before);
        assertThat(after).isEqualTo(before);
    }

    @Test
    public void anUpstreamToolThatDoesNotSayItOnlyReads_isTreatedAsAWrite() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        UPSTREAM.advertisingTo(token, new AdvertisedTool(UPSTREAM_GET_ITEM, true), AdvertisedTool.withoutAnnotations("mystery_mutation"));
        var user = connectedUser(token);
        UPSTREAM.forgetCalls();

        var response = callTool(user, "TestToken_mystery_mutation", Map.of());

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains("read-only through the gateway").doesNotContain("has not been classified");
        assertThat(UPSTREAM.callsTo("mystery_mutation")).isEmpty();
    }

    @Test
    public void aTokenThatListsAWriteToolAsReadOnly_doesNotDowngradeIt() throws Exception {
        var fullToken = newUpstreamToken();
        var otherToken = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(fullToken, otherToken);
        UPSTREAM.advertisingTo(fullToken, new AdvertisedTool(UPSTREAM_GET_ITEM, true), new AdvertisedTool(UPSTREAM_UPDATE_ITEM, false));
        UPSTREAM.advertisingTo(otherToken, new AdvertisedTool(UPSTREAM_GET_ITEM, true), new AdvertisedTool(UPSTREAM_UPDATE_ITEM, true));
        connectedUser(fullToken);
        var other = connectedUser(otherToken);
        UPSTREAM.forgetCalls();

        var response = callTool(other, UPDATE_ITEM, Map.of("id", "1"));

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains("read-only through the gateway");
        assertThat(UPSTREAM.callsTo(UPSTREAM_UPDATE_ITEM)).isEmpty();
    }

    @Test
    public void anUpstreamThatListsNoToolsForAToken_isRefusedWithAClearMessage_andKeepsNothing() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        UPSTREAM.advertisingTo(token);
        var user = signIn();

        var reply = connect(user, token);

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.body()).contains("no tools");
        assertThat(connected(user)).isFalse();
    }

    @Test
    public void aRequestFromAnotherSite_cannotConnectOrDisconnect() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = signIn();
        var before = UPSTREAM.requestsReceived();

        var connectFromElsewhere = connectFrom("https://evil.example.com", user, token);

        assertThat(connectFromElsewhere.status()).isEqualTo(403);
        assertThat(UPSTREAM.requestsReceived()).isEqualTo(before);
        assertThat(connected(user)).isFalse();
        assertThat(connectFrom(GATEWAY_ORIGIN, user, token).status()).isEqualTo(200);
        assertThat(disconnectFrom("https://evil.example.com", user).status()).isEqualTo(403);
        assertThat(connected(user)).isTrue();
        assertThat(disconnectFrom(GATEWAY_ORIGIN, user).status()).isEqualTo(200);
        assertThat(connected(user)).isFalse();
    }

    @Test
    public void aRedirectFromTheUpstream_isNotFollowed_soTheTokenGoesNowhereElse() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.respondingWithRedirect(ANOTHER_HOST.url() + "/mcp");
        var user = signIn();

        var reply = connect(user, token);

        assertThat(reply.status()).isEqualTo(502);
        assertThat(ANOTHER_HOST.requestsReceived()).isZero();
    }

    @Test
    public void aRequestWithoutAnOrigin_isNotRefusedAsCrossSite() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = signIn();

        var response = httpClient.POST(TokenMcpFixtures.path("connect"))
                .addHeader("Cookie", "SESSION=" + user.browserSession())
                .withRequestBody(new TokenMcpEndpoint.ConnectRequest(token))
                .invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void testingAConnection_addsToolsTheUpstreamHasStartedToList_forEveryone() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = connectedUser(token);
        UPSTREAM.advertisingTo(token, new AdvertisedTool(UPSTREAM_GET_ITEM, true), new AdvertisedTool("newly_listed_tool", true));
        var bystander = signIn();

        get(TokenMcpFixtures.path("test"), user);

        assertThat(listedTools(bystander)).contains(TOOL_PREFIX + "newly_listed_tool");
    }

    @Test
    public void testingWhenNotConnected_saysSo() throws Exception {
        var user = signIn();

        var test = get(TokenMcpFixtures.path("test"), user);

        assertThat(parse(test.body()).path("ok").asBoolean()).isFalse();
        assertThat(test.body()).contains("Not connected");
    }

    @Test
    public void testingWhenTheUpstreamFails_reportsItWithoutTheUpstreamsWords() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = connectedUser(token);

        UPSTREAM.respondingWithStatus(503);
        var unavailable = get(TokenMcpFixtures.path("test"), user);
        UPSTREAM.respondingWithStatus(403);
        var other = get(TokenMcpFixtures.path("test"), user);

        assertThat(unavailable.body()).contains("could not be reached").doesNotContain(FakeMcpServer.FAILURE_BODY);
        assertThat(other.body()).contains("could not be checked").doesNotContain(FakeMcpServer.FAILURE_BODY);
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void statusAndTestWithoutASession_sendThePersonToSignIn() {
        assertThat(httpClient.GET(TokenMcpFixtures.path("status")).invoke().status().intValue()).isEqualTo(302);
        assertThat(httpClient.GET(TokenMcpFixtures.path("test")).invoke().status().intValue()).isEqualTo(302);
    }

    @Test
    public void anUpstreamWriteTool_isRefusedAsAClassifiedWrite_whileWritesAreNotEnabled_andNeverReachesTheUpstream() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = connectedUser(token);
        UPSTREAM.forgetCalls();

        var response = callTool(user, UPDATE_ITEM, Map.of("id", "1"));

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains("read-only through the gateway").doesNotContain("has not been classified");
        assertThat(UPSTREAM.callsTo(UPSTREAM_UPDATE_ITEM)).isEmpty();
    }

    @Test
    public void anUpstreamWriteTool_reachesTheUpstreamOnceAnAdminEnablesWritesForIt() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = connectedUser(token);
        UPSTREAM.forgetCalls();
        GatewayFixtures.enableWritesOn(componentClient, List.of(MCP_ID));

        var response = callTool(user, UPDATE_ITEM, Map.of("id", "1"));

        assertThat(isToolError(response)).isFalse();
        assertThat(UPSTREAM.callsTo(UPSTREAM_UPDATE_ITEM)).hasSize(1);
    }

    @Test
    public void theWriteAccessPage_offersTheConnectorToAnAdmin_whenItIsSetUp() {
        var session = GatewayFixtures.browserSession(componentClient, "admin-" + UUID.randomUUID() + "@lightbend.com", List.of(ADMIN_GROUP));

        var response = httpClient.GET("/admin/write-access/data").addHeader("Cookie", "SESSION=" + session).invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
        assertThat(response.body().utf8String()).contains(MCP_ID);
    }

    @Test
    public void anUpstreamReadTool_reachesTheUpstreamForAUserWhoMayRead() throws Exception {
        var token = newUpstreamToken();
        UPSTREAM.acceptingOnlyBearer(token);
        var user = connectedUser(token);
        UPSTREAM.forgetCalls();

        var response = callTool(user, GET_ITEM, Map.of("id", "1"));

        assertThat(isToolError(response)).isFalse();
        assertThat(UPSTREAM.callsTo(UPSTREAM_GET_ITEM)).hasSize(1);
    }

    private static JsonNode parse(String json) throws Exception {
        return JsonSupport.getObjectMapper().readTree(json);
    }
}
