package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.mcp.gateway.application.CustomerHubConnectionEvent;
import io.akka.mcp.gateway.application.CustomerHubMcpClient;
import io.akka.mcp.gateway.application.McpInteractionsByUserView;
import io.akka.mcp.gateway.application.SlackMcpClient;
import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.testsupport.FakeMcpServer;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.AdvertisedTool;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.ReceivedCall;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import io.akka.mcp.gateway.testsupport.LogCapture;
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
 * The Customer Hub connector forwards each user's own pasted token to the hub, checks it with the hub before
 * keeping it, never hands it back, and keeps no arguments or results of the calls in the gateway's history or
 * logs. A fake hub stands in for the real one and refuses any request whose Bearer token it was not told to accept.
 */
public class CustomerHubIntegrationTest extends TestKitSupport {

    static final String READER_GROUP = "mcp-gateway-reader";
    static final String APP_ID = "customer-hub-app-id-under-test";
    private static final String WRITER_GROUP = "mcp-gateway-writer";
    static final String ADMIN_GROUP = "mcp-gateway-admin";
    private static final String GATEWAY_ORIGIN = "http://localhost:9000";
    private static final String MCP_ID = CustomerHubMcpClient.MCP_ID;
    private static final String SLACK_MCP_ID = SlackMcpClient.MCP_ID;
    private static final String HUB_TOOL_PREFIX = "CustomerHub_";
    private static final String GET_REQUEST = "CustomerHub_get_request";
    private static final String UPDATE_REQUEST = "CustomerHub_update_request";
    private static final String UPSTREAM_GET_REQUEST = "get_request";
    private static final String UPSTREAM_UPDATE_REQUEST = "update_request";

    private static final FakeMcpServer HUB = FakeMcpServer.start().advertising(
            new AdvertisedTool(UPSTREAM_GET_REQUEST, true),
            new AdvertisedTool(UPSTREAM_UPDATE_REQUEST, false));
    private static final FakeMcpServer SLACK = FakeMcpServer.start().advertising(
            new AdvertisedTool("slack_search_messages", true));
    private static final FakeMcpServer ANOTHER_HOST = FakeMcpServer.start();

    @AfterAll
    public static void stopFakeUpstreams() {
        HUB.close();
        SLACK.close();
        ANOTHER_HOST.close();
    }

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                customer-hub.mcp-url = "%s"
                customer-hub.okta-app-id = "%s"
                slack.mcp-url = "%s"
                mcp.base-url = "%s"
                okta.groups.reader = "%s"
                okta.groups.writer = "%s"
                okta.groups.admin = "%s"
                """.formatted(HUB.url(), APP_ID, SLACK.url(), GATEWAY_ORIGIN, READER_GROUP, WRITER_GROUP, ADMIN_GROUP));
    }

    @BeforeEach
    public void freshHubAndReadOnlyPolicy() {
        HUB.forgetCalls();
        HUB.respondingNormally();
        HUB.refusingEveryToken();
        GatewayFixtures.enableWritesOn(componentClient, List.of());
    }

    private record SignedIn(String email, String browserSession, String mcpToken) {}

    private record Reply(int status, String body) {}

    private SignedIn signIn() {
        return signIn(List.of(new UserSession.App(APP_ID, CustomerHubMcpClient.MCP_NAME)));
    }

    private SignedIn signIn(List<UserSession.App> apps) {
        var email = "hub-user-" + UUID.randomUUID() + "@lightbend.com";
        return new SignedIn(email,
                GatewayFixtures.browserSession(componentClient, email, List.of(), apps),
                GatewayFixtures.mcpToken(componentClient, email, List.of(READER_GROUP, WRITER_GROUP), apps));
    }

    private static String newHubToken() {
        return "hubtoken-" + UUID.randomUUID();
    }

    private Reply connect(SignedIn user, String hubToken) {
        return connectFrom(GATEWAY_ORIGIN, user, hubToken);
    }

    private Reply connectFrom(String origin, SignedIn user, String hubToken) {
        var response = httpClient.POST("/customer-hub/connect")
                .addHeader("Cookie", "SESSION=" + user.browserSession())
                .addHeader("Origin", origin)
                .withRequestBody(new CustomerHubEndpoint.ConnectRequest(hubToken))
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
        var response = httpClient.POST("/customer-hub/disconnect")
                .addHeader("Cookie", "SESSION=" + user.browserSession())
                .addHeader("Origin", origin)
                .invoke();
        return new Reply(response.status().intValue(), response.body().utf8String());
    }

    private boolean connected(SignedIn user) throws Exception {
        return parse(get("/customer-hub/status", user).body()).path("connected").asBoolean();
    }

    private SignedIn connectedUser(String hubToken) {
        var user = signIn();
        assertThat(connect(user, hubToken).status()).as("connecting with an accepted token").isEqualTo(200);
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

    private static List<String> hubTools(List<String> tools) {
        return tools.stream().filter(name -> name.startsWith(HUB_TOOL_PREFIX)).sorted().toList();
    }

    @Test
    public void connectingWithATokenTheHubAccepts_keepsIt_andNoRouteReturnsIt() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = signIn();

        var reply = connect(user, token);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(parse(reply.body()).path("ok").asBoolean()).isTrue();
        assertThat(reply.body()).contains(GET_REQUEST).doesNotContain(token);
        var status = get("/customer-hub/status", user);
        assertThat(parse(status.body()).path("connected").asBoolean()).isTrue();
        assertThat(status.body()).doesNotContain(token);
        var test = get("/customer-hub/test", user);
        assertThat(parse(test.body()).path("ok").asBoolean()).isTrue();
        assertThat(test.body()).doesNotContain(token);
    }

    @Test
    public void connectingWithATokenTheHubRefuses_keepsNothing() throws Exception {
        var user = signIn();

        var reply = connect(user, newHubToken());

        assertThat(reply.status()).isEqualTo(400);
        assertThat(parse(reply.body()).path("ok").asBoolean()).isFalse();
        assertThat(connected(user)).isFalse();
    }

    @Test
    public void aRefusedToken_leavesAnExistingConnectionIntact() throws Exception {
        var good = newHubToken();
        HUB.acceptingOnlyBearer(good);
        var user = connectedUser(good);

        var reply = connect(user, newHubToken());

        assertThat(reply.status()).isEqualTo(400);
        assertThat(connected(user)).isTrue();
        HUB.forgetCalls();
        assertThat(isToolError(callTool(user, GET_REQUEST, Map.of()))).isFalse();
        assertThat(HUB.callsTo(UPSTREAM_GET_REQUEST)).extracting(ReceivedCall::authorization).containsExactly("Bearer " + good);
    }

    @Test
    public void connectingAgain_replacesTheToken() throws Exception {
        var first = newHubToken();
        var second = newHubToken();
        HUB.acceptingOnlyBearer(first, second);
        var user = connectedUser(first);

        assertThat(connect(user, second).status()).isEqualTo(200);

        HUB.forgetCalls();
        callTool(user, GET_REQUEST, Map.of());
        assertThat(HUB.callsTo(UPSTREAM_GET_REQUEST)).extracting(ReceivedCall::authorization).containsExactly("Bearer " + second);
    }

    @Test
    public void connectingWithoutASession_isRefused_andTheHubIsNotAsked() {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var before = HUB.requestsReceived();

        var response = httpClient.POST("/customer-hub/connect")
                .withRequestBody(new CustomerHubEndpoint.ConnectRequest(token))
                .invoke();

        assertThat(response.status().intValue()).isEqualTo(401);
        assertThat(HUB.requestsReceived()).isEqualTo(before);
    }

    @Test
    public void connectingWithoutTheOktaApp_isForbidden_andTheHubIsNotAsked() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = signIn(List.of());
        var before = HUB.requestsReceived();

        var reply = connect(user, token);

        assertThat(reply.status()).isEqualTo(403);
        assertThat(HUB.requestsReceived()).isEqualTo(before);
        awaitInteractionsWith(user, MCP_ID, CustomerHubConnectionEvent.CONNECT_BLOCKED.direction());
    }

    @Test
    public void testingWithoutTheOktaApp_isForbidden() {
        var user = signIn(List.of());

        assertThat(get("/customer-hub/test", user).status()).isEqualTo(403);
    }

    @Test
    public void aMalformedToken_isRefused_andTheHubIsNotAsked() throws Exception {
        var user = signIn();
        var before = HUB.requestsReceived();

        var withInjectedHeader = connect(user, "hubtoken_abc\r\nX-Injected: 1");
        var withSpace = connect(user, "hubtoken abc");
        var empty = connect(user, "   ");

        assertThat(List.of(withInjectedHeader.status(), withSpace.status(), empty.status())).containsOnly(400);
        assertThat(HUB.requestsReceived()).isEqualTo(before);
        assertThat(connected(user)).isFalse();
    }

    @Test
    public void aTokenPastedWithWhitespaceAndABearerPrefix_isCleanedBeforeUse() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = signIn();

        var reply = connect(user, "  Bearer " + token + "\n");

        assertThat(reply.status()).isEqualTo(200);
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void whenTheHubFailsWhileConnecting_thePersonIsToldWithoutTheHubsWords_andNothingIsKept() throws Exception {
        var user = signIn();

        HUB.respondingWithStatus(503);
        var unavailable = connect(user, newHubToken());
        HUB.respondingWithStatus(403);
        var forbidden = connect(user, newHubToken());

        assertThat(unavailable.status()).isEqualTo(502);
        assertThat(unavailable.body()).contains("could not be reached").doesNotContain(FakeMcpServer.FAILURE_BODY);
        assertThat(forbidden.status()).isEqualTo(502);
        assertThat(forbidden.body()).contains("could not be checked").doesNotContain(FakeMcpServer.FAILURE_BODY);
        assertThat(connected(user)).isFalse();
    }

    @Test
    public void testingAConnection_neverDisconnects_andReportsTheFailureInPlainTerms() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = connectedUser(token);
        HUB.refusingEveryToken();

        var test = get("/customer-hub/test", user);

        assertThat(parse(test.body()).path("ok").asBoolean()).isFalse();
        assertThat(test.body()).contains("did not accept that token").doesNotContain("401").doesNotContain("Unexpected");
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void aCallForwardsEachUsersOwnTokenAsBearer_underTheHubsOwnToolName() throws Exception {
        var tokenA = newHubToken();
        var tokenB = newHubToken();
        HUB.acceptingOnlyBearer(tokenA, tokenB);
        var userA = connectedUser(tokenA);
        var userB = connectedUser(tokenB);
        HUB.forgetCalls();

        var first = callTool(userA, GET_REQUEST, Map.of("id", "1"));
        var second = callTool(userB, GET_REQUEST, Map.of("id", "2"));

        assertThat(isToolError(first)).isFalse();
        assertThat(isToolError(second)).isFalse();
        assertThat(HUB.callsTo(UPSTREAM_GET_REQUEST)).extracting(ReceivedCall::authorization)
                .containsExactly("Bearer " + tokenA, "Bearer " + tokenB);
        assertThat(HUB.callsTo(GET_REQUEST)).isEmpty();
    }

    @Test
    public void aCallBeforeConnecting_saysTheHubIsNotConnected_andNothingReachesTheHub() throws Exception {
        var user = signIn();

        var response = callTool(user, GET_REQUEST, Map.of("id", "1"));

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains("Customer Hub is not connected");
        assertThat(HUB.callsTo(UPSTREAM_GET_REQUEST)).isEmpty();
    }

    @Test
    public void whenTheHubRefusesTheStoredToken_theUserIsDisconnected_toldToReconnect_andItIsRecorded() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = connectedUser(token);
        HUB.refusingEveryToken();

        var response = callTool(user, GET_REQUEST, Map.of("id", "1"));

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains("did not accept your token").contains("connect again");
        assertThat(connected(user)).isFalse();
        assertThat(toolText(callTool(user, GET_REQUEST, Map.of("id", "1"))))
                .contains("Customer Hub is not connected");
        awaitInteractionsWith(user, MCP_ID, CustomerHubConnectionEvent.CONNECT_SUCCESS.direction(), CustomerHubConnectionEvent.DISCONNECT.direction());
    }

    @Test
    public void aHubThatRefusesOnlyTheCall_disconnectsTheUser_andSaysNothingOfTheStatus() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = connectedUser(token);
        HUB.respondingToCallsWithStatus(401);

        var response = callTool(user, GET_REQUEST, Map.of());

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains("did not accept your token")
                .doesNotContain("Unexpected").doesNotContain("401").doesNotContain(FakeMcpServer.FAILURE_BODY);
        assertThat(connected(user)).isFalse();
    }

    @Test
    public void aHubThatFailsOnlyTheCall_keepsTheToken_andSaysItIsUnavailable() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = connectedUser(token);

        HUB.respondingToCallsWithStatus(503);
        var unavailable = callTool(user, GET_REQUEST, Map.of());
        HUB.respondingToCallsWithStatus(403);
        var refused = callTool(user, GET_REQUEST, Map.of());

        assertThat(toolText(unavailable)).contains("temporarily unavailable")
                .doesNotContain("Unexpected").doesNotContain(FakeMcpServer.FAILURE_BODY);
        assertThat(toolText(refused)).contains("request failed")
                .doesNotContain("Unexpected").doesNotContain(FakeMcpServer.FAILURE_BODY);
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void toolsList_doesNotAskTheHubOfAUserWhoseToolsAreCached() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = connectedUser(token);
        var before = HUB.requestsReceived();

        var tools = hubTools(listedTools(user));

        assertThat(tools).isNotEmpty();
        assertThat(HUB.requestsReceived()).isEqualTo(before);
    }

    @Test
    public void whenTheHubIsDown_theTokenIsKept_andTheCallWorksAgainOnceItIsBack() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = connectedUser(token);

        HUB.respondingWithStatus(503);
        var whileDown = callTool(user, GET_REQUEST, Map.of());
        HUB.respondingWithStatus(429);
        var whileThrottled = callTool(user, GET_REQUEST, Map.of());
        HUB.respondingNormally();
        var afterwards = callTool(user, GET_REQUEST, Map.of());

        assertThat(toolText(whileDown)).contains("temporarily unavailable");
        assertThat(toolText(whileThrottled)).contains("temporarily unavailable");
        assertThat(isToolError(afterwards)).isFalse();
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void whenTheHubRefusesTheCallForAnotherReason_theTokenIsKept() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = connectedUser(token);

        HUB.respondingWithStatus(403);
        var response = callTool(user, GET_REQUEST, Map.of());

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains("request failed");
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void disconnecting_forgetsTheToken_recordsIt_andLaterCallsAreNotConnected() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = connectedUser(token);

        assertThat(disconnect(user).status()).isEqualTo(200);

        assertThat(connected(user)).isFalse();
        assertThat(toolText(callTool(user, GET_REQUEST, Map.of()))).contains("Customer Hub is not connected");
        awaitInteractionsWith(user, MCP_ID, CustomerHubConnectionEvent.CONNECT_ATTEMPT.direction(), CustomerHubConnectionEvent.CONNECT_SUCCESS.direction(), CustomerHubConnectionEvent.DISCONNECT.direction());
    }

    @Test
    public void aFailedConnect_isRecordedWithoutTheToken() throws Exception {
        var user = signIn();
        var token = newHubToken();

        connect(user, token);

        var recorded = awaitInteractionsWith(user, MCP_ID, CustomerHubConnectionEvent.CONNECT_ATTEMPT.direction(), CustomerHubConnectionEvent.CONNECT_FAILED.direction());
        assertThat(recorded).anySatisfy(entry -> assertThat(entry.output()).contains("TOKEN_REJECTED"));
        assertThat(recorded).allSatisfy(entry -> {
            assertThat(entry.params()).doesNotContain(token);
            assertThat(entry.output().orElse("")).doesNotContain(token);
        });
    }

    @Test
    public void disconnectingWithoutASession_isRefused() {
        var response = httpClient.POST("/customer-hub/disconnect").invoke();

        assertThat(response.status().intValue()).isEqualTo(401);
    }

    @Test
    public void theGatewayKeepsNoArgumentsOrResultsOfCustomerHubCalls() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = connectedUser(token);

        var response = callTool(user, GET_REQUEST, Map.of("id", "REQUEST-ARGUMENT-123"));

        assertThat(isToolError(response)).isFalse();
        assertThat(toolText(response)).contains(FakeMcpServer.HANDLED_PREFIX + UPSTREAM_GET_REQUEST);
        var recorded = awaitInteractionsWith(user, MCP_ID, "req", "resp");
        assertThat(recorded).allSatisfy(entry -> {
            assertThat(entry.params()).doesNotContain("REQUEST-ARGUMENT-123");
            assertThat(entry.output().orElse("")).doesNotContain(FakeMcpServer.HANDLED_PREFIX.trim());
        });
        assertThat(recorded).anySatisfy(entry -> assertThat(entry.params()).contains(AkkaMcpGateway.NOT_KEPT));
        assertThat(recorded).anySatisfy(entry -> assertThat(entry.output()).contains(AkkaMcpGateway.NOT_KEPT));
    }

    /** Control: a connector that keeps content does record its arguments and results, so keeping them would be noticed. */
    @Test
    public void otherConnectorsStillKeepTheirArgumentsAndResults() throws Exception {
        var user = signIn();
        GatewayFixtures.connectSlack(componentClient, user.email());
        listedTools(user);

        callTool(user, "slack_search_messages", Map.of("query", "SLACK-ARGUMENT-456"));

        var recorded = awaitInteractionsWith(user, SLACK_MCP_ID, "req", "resp");
        assertThat(recorded).anySatisfy(entry -> assertThat(entry.params()).contains("SLACK-ARGUMENT-456"));
        assertThat(recorded).anySatisfy(entry -> assertThat(entry.output()).contains(FakeMcpServer.HANDLED_PREFIX + "slack_search_messages"));
    }

    private SignedIn connectedUserWithErrorTools(String token) {
        HUB.acceptingOnlyBearer(token);
        HUB.advertisingTo(token,
                new AdvertisedTool(UPSTREAM_GET_REQUEST, true),
                new AdvertisedTool(FakeMcpServer.ERROR_RESULT_TOOL, true),
                new AdvertisedTool(FakeMcpServer.PROTOCOL_ERROR_TOOL, true));
        return connectedUser(token);
    }

    @Test
    public void callBodiesResultsAndHubErrorsAreNeverLogged() throws Throwable {
        var token = newHubToken();
        var user = signIn();
        var malformedBody = "{\"params\":{\"arguments\":{\"id\":\"MALFORMED-ARGUMENT-321\"}} oops".getBytes(StandardCharsets.UTF_8);
        var malformedConnect = "{\"token\":\"LEAKED-TOKEN-555\" oops".getBytes(StandardCharsets.UTF_8);

        var logged = LogCapture.during(() -> {
            HUB.acceptingOnlyBearer(token);
            HUB.advertisingTo(token,
                    new AdvertisedTool(UPSTREAM_GET_REQUEST, true),
                    new AdvertisedTool(FakeMcpServer.ERROR_RESULT_TOOL, true),
                    new AdvertisedTool(FakeMcpServer.PROTOCOL_ERROR_TOOL, true));
            connect(user, token);
            callTool(user, GET_REQUEST, Map.of("id", "LOGGED-ARGUMENT-789"));
            callTool(user, HUB_TOOL_PREFIX + FakeMcpServer.ERROR_RESULT_TOOL, Map.of());
            callTool(user, HUB_TOOL_PREFIX + FakeMcpServer.PROTOCOL_ERROR_TOOL, Map.of());
            HUB.respondingWithStatus(503);
            callTool(user, GET_REQUEST, Map.of());
            listedTools(user);
            HUB.respondingNormally();
            httpClient.POST("/mcp")
                    .addHeader("Authorization", "Bearer " + user.mcpToken())
                    .withRequestBody(ContentTypes.APPLICATION_JSON, malformedBody)
                    .invoke();
            httpClient.POST("/customer-hub/connect")
                    .addHeader("Cookie", "SESSION=" + user.browserSession())
                    .withRequestBody(ContentTypes.APPLICATION_JSON, malformedConnect)
                    .invoke();
        });

        assertThat(logged).noneMatch(line -> line.contains("LOGGED-ARGUMENT-789")
                || line.contains("MALFORMED-ARGUMENT-321")
                || line.contains("LEAKED-TOKEN-555")
                || line.contains(FakeMcpServer.HANDLED_PREFIX + UPSTREAM_GET_REQUEST)
                || line.contains(FakeMcpServer.ERROR_RESULT_TEXT)
                || line.contains(FakeMcpServer.PROTOCOL_ERROR_TEXT)
                || line.contains(token));
    }

    @Test
    public void aToolErrorFromTheHub_reachesTheCaller_butIsNotKeptInTheHistory() throws Exception {
        var user = connectedUserWithErrorTools(newHubToken());

        var response = callTool(user, HUB_TOOL_PREFIX + FakeMcpServer.ERROR_RESULT_TOOL, Map.of());

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains(FakeMcpServer.ERROR_RESULT_TEXT);
        assertThat(awaitInteractionsWith(user, MCP_ID, "req", "resp")).allSatisfy(entry -> {
            assertThat(entry.params()).doesNotContain(FakeMcpServer.ERROR_RESULT_TEXT);
            assertThat(entry.output().orElse("")).doesNotContain(FakeMcpServer.ERROR_RESULT_TEXT);
        });
    }

    @Test
    public void aProtocolErrorFromTheHub_reachesTheCaller_butIsNotKeptInTheHistory() throws Exception {
        var user = connectedUserWithErrorTools(newHubToken());

        var response = callTool(user, HUB_TOOL_PREFIX + FakeMcpServer.PROTOCOL_ERROR_TOOL, Map.of());

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

        var response = httpClient.POST("/customer-hub/connect")
                .addHeader("Cookie", "SESSION=" + user.browserSession())
                .withRequestBody(ContentTypes.APPLICATION_JSON, "{}".getBytes(StandardCharsets.UTF_8))
                .invoke();

        assertThat(response.status().intValue()).isEqualTo(400);
    }

    @Test
    public void anOriginIsComparedByItsSchemeHostAndPort_notByItsSpelling() {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = signIn();

        assertThat(connectFrom("http://localhost:9001", user, token).status()).isEqualTo(403);
        assertThat(connectFrom("not an origin", user, token).status()).isEqualTo(403);
        assertThat(connectFrom("HTTP://LOCALHOST:9000", user, token).status()).isEqualTo(200);
    }

    @Test
    public void everyUserSeesTheHubTools_onceAnyoneHasConnected() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        connectedUser(token);
        var neverConnected = signIn();

        assertThat(listedTools(neverConnected)).contains(GET_REQUEST, UPDATE_REQUEST);
    }

    @Test
    public void everyoneIsListedTheWholeHubToolList_whateverTheirTokenSees() throws Exception {
        var fullToken = newHubToken();
        var restrictedToken = newHubToken();
        HUB.acceptingOnlyBearer(fullToken, restrictedToken);
        HUB.advertisingTo(fullToken,
                new AdvertisedTool(UPSTREAM_GET_REQUEST, true),
                new AdvertisedTool(UPSTREAM_UPDATE_REQUEST, false),
                new AdvertisedTool("only_on_the_full_list", true));
        HUB.advertisingTo(restrictedToken, new AdvertisedTool(UPSTREAM_GET_REQUEST, true));
        connectedUser(fullToken);

        var restricted = connectedUser(restrictedToken);
        var bystander = signIn();

        assertThat(listedTools(restricted)).contains("CustomerHub_only_on_the_full_list", UPDATE_REQUEST);
        assertThat(listedTools(bystander)).contains("CustomerHub_only_on_the_full_list", UPDATE_REQUEST);
    }

    @Test
    public void aUsersToolList_doesNotChange_whenTheyConnectOrDisconnect() throws Exception {
        var warmingToken = newHubToken();
        var token = newHubToken();
        HUB.acceptingOnlyBearer(warmingToken, token);
        connectedUser(warmingToken);
        var user = signIn();

        var before = hubTools(listedTools(user));
        connect(user, token);
        var whileConnected = hubTools(listedTools(user));
        disconnect(user);
        var after = hubTools(listedTools(user));

        assertThat(before).isNotEmpty();
        assertThat(whileConnected).isEqualTo(before);
        assertThat(after).isEqualTo(before);
    }

    @Test
    public void aHubToolThatDoesNotSayItOnlyReads_isTreatedAsAWrite() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        HUB.advertisingTo(token, new AdvertisedTool(UPSTREAM_GET_REQUEST, true), AdvertisedTool.withoutAnnotations("mystery_mutation"));
        var user = connectedUser(token);
        HUB.forgetCalls();

        var response = callTool(user, "CustomerHub_mystery_mutation", Map.of());

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains("read-only through the gateway").doesNotContain("has not been classified");
        assertThat(HUB.callsTo("mystery_mutation")).isEmpty();
    }

    @Test
    public void aTokenThatListsAWriteToolAsReadOnly_doesNotDowngradeIt() throws Exception {
        var fullToken = newHubToken();
        var otherToken = newHubToken();
        HUB.acceptingOnlyBearer(fullToken, otherToken);
        HUB.advertisingTo(fullToken, new AdvertisedTool(UPSTREAM_GET_REQUEST, true), new AdvertisedTool(UPSTREAM_UPDATE_REQUEST, false));
        HUB.advertisingTo(otherToken, new AdvertisedTool(UPSTREAM_GET_REQUEST, true), new AdvertisedTool(UPSTREAM_UPDATE_REQUEST, true));
        connectedUser(fullToken);
        var other = connectedUser(otherToken);
        HUB.forgetCalls();

        var response = callTool(other, UPDATE_REQUEST, Map.of("id", "1"));

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains("read-only through the gateway");
        assertThat(HUB.callsTo(UPSTREAM_UPDATE_REQUEST)).isEmpty();
    }

    @Test
    public void aHubThatListsNoToolsForAToken_isRefusedWithAClearMessage_andKeepsNothing() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        HUB.advertisingTo(token);
        var user = signIn();

        var reply = connect(user, token);

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.body()).contains("no tools");
        assertThat(connected(user)).isFalse();
    }

    @Test
    public void aRequestFromAnotherSite_cannotConnectOrDisconnect() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = signIn();
        var before = HUB.requestsReceived();

        var connectFromElsewhere = connectFrom("https://evil.example.com", user, token);

        assertThat(connectFromElsewhere.status()).isEqualTo(403);
        assertThat(HUB.requestsReceived()).isEqualTo(before);
        assertThat(connected(user)).isFalse();
        assertThat(connectFrom(GATEWAY_ORIGIN, user, token).status()).isEqualTo(200);
        assertThat(disconnectFrom("https://evil.example.com", user).status()).isEqualTo(403);
        assertThat(connected(user)).isTrue();
        assertThat(disconnectFrom(GATEWAY_ORIGIN, user).status()).isEqualTo(200);
        assertThat(connected(user)).isFalse();
    }

    @Test
    public void aRedirectFromTheHub_isNotFollowed_soTheTokenGoesNowhereElse() throws Exception {
        var token = newHubToken();
        HUB.respondingWithRedirect(ANOTHER_HOST.url() + "/mcp");
        var user = signIn();

        var reply = connect(user, token);

        assertThat(reply.status()).isEqualTo(502);
        assertThat(ANOTHER_HOST.requestsReceived()).isZero();
    }

    @Test
    public void aRequestWithoutAnOrigin_isNotRefusedAsCrossSite() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = signIn();

        var response = httpClient.POST("/customer-hub/connect")
                .addHeader("Cookie", "SESSION=" + user.browserSession())
                .withRequestBody(new CustomerHubEndpoint.ConnectRequest(token))
                .invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void testingAConnection_addsToolsTheHubHasStartedToList_forEveryone() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = connectedUser(token);
        HUB.advertisingTo(token, new AdvertisedTool(UPSTREAM_GET_REQUEST, true), new AdvertisedTool("newly_listed_tool", true));
        var bystander = signIn();

        get("/customer-hub/test", user);

        assertThat(listedTools(bystander)).contains(HUB_TOOL_PREFIX + "newly_listed_tool");
    }

    @Test
    public void testingWhenNotConnected_saysSo() throws Exception {
        var user = signIn();

        var test = get("/customer-hub/test", user);

        assertThat(parse(test.body()).path("ok").asBoolean()).isFalse();
        assertThat(test.body()).contains("Not connected");
    }

    @Test
    public void testingWhenTheHubFails_reportsItWithoutTheHubsWords() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = connectedUser(token);

        HUB.respondingWithStatus(503);
        var unavailable = get("/customer-hub/test", user);
        HUB.respondingWithStatus(403);
        var other = get("/customer-hub/test", user);

        assertThat(unavailable.body()).contains("could not be reached").doesNotContain(FakeMcpServer.FAILURE_BODY);
        assertThat(other.body()).contains("could not be checked").doesNotContain(FakeMcpServer.FAILURE_BODY);
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void statusAndTestWithoutASession_sendThePersonToSignIn() {
        assertThat(httpClient.GET("/customer-hub/status").invoke().status().intValue()).isEqualTo(302);
        assertThat(httpClient.GET("/customer-hub/test").invoke().status().intValue()).isEqualTo(302);
    }

    @Test
    public void aHubWriteTool_isRefusedAsAClassifiedWrite_whileWritesAreNotEnabled_andNeverReachesTheHub() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = connectedUser(token);
        HUB.forgetCalls();

        var response = callTool(user, UPDATE_REQUEST, Map.of("id", "1"));

        assertThat(isToolError(response)).isTrue();
        assertThat(toolText(response)).contains("read-only through the gateway").doesNotContain("has not been classified");
        assertThat(HUB.callsTo(UPSTREAM_UPDATE_REQUEST)).isEmpty();
    }

    @Test
    public void aHubWriteTool_reachesTheHubOnceAnAdminEnablesWritesForIt() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = connectedUser(token);
        HUB.forgetCalls();
        GatewayFixtures.enableWritesOn(componentClient, List.of(MCP_ID));

        var response = callTool(user, UPDATE_REQUEST, Map.of("id", "1"));

        assertThat(isToolError(response)).isFalse();
        assertThat(HUB.callsTo(UPSTREAM_UPDATE_REQUEST)).hasSize(1);
    }

    @Test
    public void theWriteAccessPage_offersTheHubToAnAdmin_whenItIsSetUp() {
        var session = GatewayFixtures.browserSession(componentClient, "hub-admin-" + UUID.randomUUID() + "@lightbend.com", List.of(ADMIN_GROUP));

        var response = httpClient.GET("/admin/write-access/data").addHeader("Cookie", "SESSION=" + session).invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
        assertThat(response.body().utf8String()).contains(MCP_ID);
    }

    @Test
    public void aHubReadTool_reachesTheHubForAReader() throws Exception {
        var token = newHubToken();
        HUB.acceptingOnlyBearer(token);
        var user = connectedUser(token);
        HUB.forgetCalls();

        var response = callTool(user, GET_REQUEST, Map.of("id", "1"));

        assertThat(isToolError(response)).isFalse();
        assertThat(HUB.callsTo(UPSTREAM_GET_REQUEST)).hasSize(1);
    }

    private static JsonNode parse(String json) throws Exception {
        return JsonSupport.getObjectMapper().readTree(json);
    }
}
