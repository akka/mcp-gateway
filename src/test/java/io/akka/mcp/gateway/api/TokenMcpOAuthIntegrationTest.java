package io.akka.mcp.gateway.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.mcp.gateway.application.McpInteractionsByUserView;
import io.akka.mcp.gateway.application.OAuthRegistrationEntity;
import io.akka.mcp.gateway.application.OAuthTokenConnectionEntity;
import io.akka.mcp.gateway.application.TokenConnectionEntity;
import io.akka.mcp.gateway.domain.OAuthTokenConnection.Tokens;
import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.testsupport.FakeMcpServer;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.AdvertisedTool;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.ReceivedCall;
import io.akka.mcp.gateway.testsupport.FakeOAuthServer;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import io.akka.mcp.gateway.testsupport.TokenMcpFixtures;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An OAuth token connector sends the browser to the server's own sign-in, keeps the access the server issues,
 * renews it when it expires, and never hands it back. A fake server with dynamic client registration and PKCE
 * stands in for the real one, and the test plays the browser.
 */
public class TokenMcpOAuthIntegrationTest extends TestKitSupport {

    private static final String READER_GROUP = "mcp-gateway-reader";
    private static final String WRITER_GROUP = "mcp-gateway-writer";
    private static final String APP_ID = "oauth-service-app-id-under-test";
    private static final String GATEWAY_ORIGIN = "http://localhost:9000";
    private static final String CALLBACK = GATEWAY_ORIGIN + "/token-mcp-oauth/callback";
    private static final String GET_ITEM = TokenMcpFixtures.TOOL_PREFIX + "get_item";
    private static final String STATUS_PATH = "/token-mcp/" + TokenMcpFixtures.ID + "/status";
    private static final String DISCONNECT_PATH = "/token-mcp/" + TokenMcpFixtures.ID + "/disconnect";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final FakeMcpServer UPSTREAM = FakeMcpServer.start().advertising(new AdvertisedTool("get_item", true));
    private static final FakeOAuthServer OAUTH = FakeOAuthServer.mountedOn(UPSTREAM);

    @AfterAll
    public static void stopFakeUpstream() {
        UPSTREAM.close();
    }

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                %s
                mcp.base-url = "%s"
                okta.groups.reader = "%s"
                okta.groups.writer = "%s"
                """.formatted(TokenMcpFixtures.oauthSetting(UPSTREAM.url(), APP_ID), GATEWAY_ORIGIN, READER_GROUP, WRITER_GROUP));
    }

    @BeforeEach
    public void freshUpstream() {
        UPSTREAM.forgetCalls();
        UPSTREAM.respondingNormally();
        UPSTREAM.advertising(new AdvertisedTool("get_item", true));
        OAUTH.reset();
        forgetTheClientRegistration();
        GatewayFixtures.enableWritesOn(componentClient, List.of());
    }

    private void forgetTheClientRegistration() {
        componentClient.forKeyValueEntity(TokenMcpFixtures.ID).method(OAuthRegistrationEntity::forget).invoke();
    }

    private record SignedIn(String email, String browserSession, String mcpToken) {}

    private record Reply(int status, String body, String location) {}

    private SignedIn signIn() {
        return signIn(List.of(new UserSession.App(APP_ID, TokenMcpFixtures.NAME)));
    }

    private SignedIn signIn(List<UserSession.App> apps) {
        var email = "oauth-user-" + UUID.randomUUID() + "@lightbend.com";
        return new SignedIn(email,
                GatewayFixtures.browserSession(componentClient, email, List.of(), apps),
                GatewayFixtures.mcpToken(componentClient, email, List.of(READER_GROUP, WRITER_GROUP), apps));
    }

    private Reply request(String path, Optional<SignedIn> user, Map<String, String> headers) {
        var call = httpClient.GET(path);
        if (user.isPresent()) call = call.addHeader("Cookie", "SESSION=" + user.get().browserSession());
        for (var header : headers.entrySet()) call = call.addHeader(header.getKey(), header.getValue());
        var response = call.invoke();
        return new Reply(response.status().intValue(), response.body().utf8String(),
                response.httpResponse().getHeader("Location").map(header -> header.value()).orElse(""));
    }

    private Reply get(String path, SignedIn user) {
        return request(path, Optional.of(user), Map.of());
    }

    private Reply startConnecting(SignedIn user) {
        return get(connectPath(TokenMcpFixtures.ID), user);
    }

    private static String connectPath(String id) {
        return TokenMcpFixtures.oauthPath(id + "/connect");
    }

    private Reply returnToGateway(SignedIn user, String code, String state) {
        return get("/token-mcp-oauth/callback?code=" + code + "&state=" + state, user);
    }

    private static String stateOf(Reply started) {
        return FakeOAuthServer.queryOf(URI.create(started.location())).get("state");
    }

    private void disconnect(SignedIn user) {
        httpClient.POST(DISCONNECT_PATH).addHeader("Cookie", "SESSION=" + user.browserSession()).addHeader("Origin", GATEWAY_ORIGIN).invoke();
    }

    private SignedIn connectedUser() {
        var user = signIn();
        assertThat(completeSignIn(user).status()).as("completing the sign-in").isEqualTo(302);
        return user;
    }

    private static final AtomicBoolean TOOLS_CACHED = new AtomicBoolean();
    private String seededAccessToken = "";

    /** A user whose tokens were issued earlier, so a test can say exactly where they are in their life. */
    private SignedIn userWithTokens(boolean refreshable, Duration issuedAgo, Duration expiresIn) {
        if (!TOOLS_CACHED.get()) {
            cacheTheTools();
            TOOLS_CACHED.set(true);
        }
        return seededUser(refreshable, issuedAgo, expiresIn);
    }

    /** The gateway classifies a tool as read or write from the listing it cached when someone connected or tested. */
    private void cacheTheTools() {
        var user = seededUser(true, Duration.ofMinutes(1), Duration.ofHours(1));
        var test = httpClient.POST("/token-mcp/" + TokenMcpFixtures.ID + "/test")
                .addHeader("Cookie", "SESSION=" + user.browserSession()).addHeader("Origin", GATEWAY_ORIGIN).invoke();
        assertThat(test.body().utf8String()).as("caching the tools").contains(GET_ITEM);
    }

    private SignedIn seededUser(boolean refreshable, Duration issuedAgo, Duration expiresIn) {
        var user = signIn();
        var direct = OAUTH.issueDirectly();
        seededAccessToken = direct.accessToken();
        var now = Instant.now();
        var tokens = Tokens.issued(direct.accessToken(), refreshable ? Optional.of(direct.refreshToken()) : Optional.empty(),
                now.minus(issuedAgo), now.plus(expiresIn), "client-seeded", UPSTREAM.url() + "/oauth2/token", UPSTREAM.url() + "/mcp");
        componentClient.forKeyValueEntity(TokenConnectionEntity.keyFor(TokenMcpFixtures.ID, user.email()))
                .method(OAuthTokenConnectionEntity::connect).invoke(tokens);
        return user;
    }

    private SignedIn userDueForRenewal() {
        return userWithTokens(true, Duration.ofHours(1), Duration.ofSeconds(-5));
    }

    private Reply completeSignIn(SignedIn user) {
        var started = startConnecting(user);
        assertThat(started.status()).as("sign-in starts with a redirect").isEqualTo(302);
        return returnToGateway(user, OAUTH.approve(started.location()), stateOf(started));
    }

    private boolean connected(SignedIn user) throws Exception {
        return MAPPER.readTree(get(STATUS_PATH, user).body()).path("connected").asBoolean();
    }

    private JsonNode rpc(SignedIn user, Map<String, Object> request) throws Exception {
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + user.mcpToken())
                .withRequestBody(request)
                .responseBodyAs(String.class)
                .invoke();
        return MAPPER.readTree(response.body());
    }

    private JsonNode callTool(SignedIn user, String toolName) throws Exception {
        return rpc(user, Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", toolName, "arguments", Map.of())));
    }

    private static boolean isToolError(JsonNode response) {
        return response.path("result").path("isError").asBoolean(false);
    }

    private static String toolText(JsonNode response) {
        return response.path("result").path("content").path(0).path("text").asText();
    }

    private List<McpInteractionsByUserView.McpInteractionEntry> awaitInteractionsWith(SignedIn user, String... directions) {
        var found = new ArrayList<McpInteractionsByUserView.McpInteractionEntry>();
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            var entries = componentClient.forView()
                    .method(McpInteractionsByUserView::getByUser)
                    .invoke(new McpInteractionsByUserView.UserPageRequest(user.email(), 0, 100))
                    .interactions().stream().filter(entry -> entry.mcpId().equals(TokenMcpFixtures.ID)).toList();
            assertThat(entries).extracting(McpInteractionsByUserView.McpInteractionEntry::direction).contains(directions);
            found.clear();
            found.addAll(entries);
        });
        return found;
    }

    @Test
    public void theConnectorList_tellsTheDashboardToSignInInsteadOfPasting() throws Exception {
        var connectors = MAPPER.readTree(get("/token-mcp/connectors", signIn()).body());

        assertThat(connectors.path(0).path("id").asText()).isEqualTo(TokenMcpFixtures.ID);
        assertThat(connectors.path(0).path("oauth").asBoolean()).isTrue();
    }

    @Test
    public void startingASignIn_registersTheGateway_andSendsTheBrowserToTheServerWithPkce() {
        var started = startConnecting(signIn());

        assertThat(started.status()).isEqualTo(302);
        assertThat(started.location()).startsWith(UPSTREAM.url() + "/oauth2/authorize?");
        var params = FakeOAuthServer.queryOf(URI.create(started.location()));
        assertThat(params).containsEntry("response_type", "code")
                .containsEntry("redirect_uri", CALLBACK)
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("scope", "mcp:read")
                .containsEntry("resource", UPSTREAM.url() + "/mcp");
        assertThat(params.get("client_id")).startsWith("client-");
        assertThat(params.get("state")).startsWith(TokenMcpFixtures.ID + ".");
        assertThat(OAUTH.registrations()).hasSize(1);
        assertThat(OAUTH.registrations().get(0)).contains(CALLBACK).contains("refresh_token").contains("\"token_endpoint_auth_method\":\"none\"");
    }

    @Test
    public void theScope_isTakenFromTheServerMetadata_whenTheResourceListsNone() {
        OAUTH.listingScopesOnlyInTheServerMetadata();

        var started = startConnecting(signIn());

        assertThat(FakeOAuthServer.queryOf(URI.create(started.location()))).containsEntry("scope", "mcp:read");
    }

    @Test
    public void theResourceMetadata_isFoundFromTheChallenge_whenTheServerNamesIt() {
        var hits = new AtomicInteger();
        UPSTREAM.serving("/prm-named-in-the-challenge", exchange -> {
            hits.incrementAndGet();
            var body = ("{\"resource\":\"" + UPSTREAM.url() + "/mcp\",\"authorization_servers\":[\"" + UPSTREAM.url() + "\"]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        UPSTREAM.challengingWith("Bearer resource_metadata=\"" + UPSTREAM.url() + "/prm-named-in-the-challenge\"");
        try {
            var started = startConnecting(signIn());

            assertThat(started.status()).isEqualTo(302);
            assertThat(hits.get()).isEqualTo(1);
        } finally {
            UPSTREAM.challengingWith("");
        }
    }

    @Test
    public void theResourceMetadata_isFoundAtTheOrigin_whenNotAtTheEndpointsPath() {
        OAUTH.publishingResourceMetadataOnlyAtTheOrigin();

        assertThat(startConnecting(signIn()).status()).isEqualTo(302);
    }

    @Test
    public void theServerMetadata_isFoundAtTheOpenIdAddress_whenNotAtTheOAuthAddress() {
        OAUTH.publishingOnlyOpenIdMetadata();

        assertThat(startConnecting(signIn()).status()).isEqualTo(302);
    }

    @Test
    public void completingTheSignIn_connectsTheUser_andCallsForwardTheIssuedAccessToken() throws Exception {
        var user = signIn();

        var reply = completeSignIn(user);

        assertThat(reply.status()).isEqualTo(302);
        assertThat(reply.location()).isEqualTo("/");
        assertThat(connected(user)).isTrue();
        var accessToken = OAUTH.issued().get(0).accessToken();
        assertThat(get(STATUS_PATH, user).body()).doesNotContain(accessToken);
        assertThat(OAUTH.tokenRequests().get(0)).containsEntry("redirect_uri", CALLBACK).containsEntry("resource", UPSTREAM.url() + "/mcp");
        UPSTREAM.forgetCalls();
        assertThat(isToolError(callTool(user, GET_ITEM))).isFalse();
        assertThat(UPSTREAM.callsTo("get_item")).extracting(ReceivedCall::authorization).containsExactly("Bearer " + accessToken);
    }

    @Test
    public void connects_andTheDisconnect_areInTheHistory() throws Exception {
        var user = connectedUser();

        disconnect(user);

        awaitInteractionsWith(user, "connect-attempt", "connect-success", "disconnect");
    }

    @Test
    public void reconnecting_replacesTheTokens() throws Exception {
        var user = connectedUser();

        assertThat(completeSignIn(user).status()).isEqualTo(302);

        UPSTREAM.forgetCalls();
        callTool(user, GET_ITEM);
        assertThat(UPSTREAM.callsTo("get_item")).extracting(ReceivedCall::authorization)
                .containsExactly("Bearer " + OAUTH.issued().get(1).accessToken());
    }

    @Test
    public void twoUsersConnecting_shareOneRegisteredClient_andBothSignInsCanBeCompleted() {
        var firstUser = signIn();
        var secondUser = signIn();
        var first = startConnecting(firstUser);
        var second = startConnecting(secondUser);

        assertThat(OAUTH.registrations()).hasSize(1);
        assertThat(returnToGateway(firstUser, OAUTH.approve(first.location()), stateOf(first)).status()).isEqualTo(302);
        assertThat(returnToGateway(secondUser, OAUTH.approve(second.location()), stateOf(second)).status()).isEqualTo(302);
    }

    @Test
    public void aUserCanHaveTwoSignInsStarted_andCompleteEither() {
        var user = signIn();
        var first = startConnecting(user);
        var second = startConnecting(user);

        assertThat(returnToGateway(user, OAUTH.approve(first.location()), stateOf(first)).status()).isEqualTo(302);
        assertThat(returnToGateway(user, OAUTH.approve(second.location()), stateOf(second)).status()).isEqualTo(302);
    }

    @Test
    public void aSignInStartedFromAnotherSite_orASiblingSite_isRefused_andRegistersNothing() {
        for (var site : List.of("cross-site", "same-site")) {
            var started = request(connectPath(TokenMcpFixtures.ID), Optional.of(signIn()), Map.of("Sec-Fetch-Site", site));

            assertThat(started.status()).as(site).isEqualTo(403);
        }
        assertThat(OAUTH.registrations()).isEmpty();
    }

    @Test
    public void aSignInStartedFromTheDashboard_orTyped_orByABrowserThatSendsNoHeader_isAccepted() {
        for (var headers : List.of(Map.of("Sec-Fetch-Site", "same-origin"), Map.of("Sec-Fetch-Site", "none"), Map.<String, String>of())) {
            assertThat(request(connectPath(TokenMcpFixtures.ID), Optional.of(signIn()), headers).status()).isEqualTo(302);
        }
    }

    @Test
    public void aCallbackWithAStateTheGatewayDidNotIssue_connectsNothing() throws Exception {
        var user = signIn();
        var started = startConnecting(user);
        var code = OAUTH.approve(started.location());

        var reply = returnToGateway(user, code, TokenMcpFixtures.ID + "." + UUID.randomUUID());

        assertThat(reply.status()).isEqualTo(400);
        assertThat(connected(user)).isFalse();
        assertThat(OAUTH.issued()).isEmpty();
    }

    @Test
    public void aCallbackNamingAConnectorThatDoesNotExist_isRefused() {
        assertThat(get("/token-mcp-oauth/callback?code=abc&state=no-such-connector.x", signIn()).status()).isEqualTo(400);
    }

    @Test
    public void aCallbackWithoutAState_orWithoutASession_connectsNothing() {
        assertThat(get("/token-mcp-oauth/callback?code=abc", signIn()).status()).isEqualTo(400);
        var withoutSession = request("/token-mcp-oauth/callback?code=abc&state=x", Optional.empty(), Map.of());
        assertThat(withoutSession.status()).isEqualTo(302);
        assertThat(withoutSession.location()).isEqualTo("/login");
    }

    @Test
    public void aCallbackForAnotherUsersSignIn_connectsNothing_andLeavesTheStartersSignInAlone() throws Exception {
        var starter = signIn();
        var other = signIn();
        var started = startConnecting(starter);
        var state = stateOf(started);
        var code = OAUTH.approve(started.location());

        assertThat(returnToGateway(other, code, state).status()).isEqualTo(400);

        assertThat(connected(other)).isFalse();
        assertThat(returnToGateway(starter, OAUTH.approve(started.location()), state).status()).isEqualTo(302);
        assertThat(connected(starter)).isTrue();
    }

    @Test
    public void aSignInCanOnlyBeCompletedOnce() {
        var user = signIn();
        var started = startConnecting(user);
        var state = stateOf(started);
        assertThat(returnToGateway(user, OAUTH.approve(started.location()), state).status()).isEqualTo(302);

        var replay = returnToGateway(user, OAUTH.approve(started.location()), state);

        assertThat(replay.status()).isEqualTo(400);
        assertThat(OAUTH.issued()).hasSize(1);
    }

    @Test
    public void aDeniedSignIn_orOneWithAnEmptyCode_connectsNothing_andCannotBeCompletedLater() throws Exception {
        var user = signIn();
        var started = startConnecting(user);
        var state = stateOf(started);
        var code = OAUTH.approve(started.location());

        assertThat(get("/token-mcp-oauth/callback?error=access_denied&state=" + state, user).status()).isEqualTo(400);

        assertThat(connected(user)).isFalse();
        assertThat(returnToGateway(user, code, state).status()).isEqualTo(400);
        assertThat(OAUTH.issued()).isEmpty();
        assertThat(awaitInteractionsWith(user, "connect-failed")).anySatisfy(entry -> assertThat(entry.output()).contains("DENIED"));
        var second = startConnecting(user);
        assertThat(returnToGateway(user, "", stateOf(second)).status()).isEqualTo(400);
        assertThat(OAUTH.issued()).isEmpty();
    }

    @Test
    public void aServerThatRefusesTheCode_connectsNothing_saysNothingItSent_andKeepsTheRegistration() throws Exception {
        var user = signIn();
        var started = startConnecting(user);

        var reply = returnToGateway(user, "a-code-the-server-never-issued", stateOf(started));

        assertThat(reply.status()).isEqualTo(502);
        assertThat(reply.body()).doesNotContain("invalid_grant");
        assertThat(connected(user)).isFalse();
        assertThat(awaitInteractionsWith(user, "connect-failed")).anySatisfy(entry -> assertThat(entry.output()).contains("TOKEN_EXCHANGE"));
        startConnecting(user);
        assertThat(OAUTH.registrations()).hasSize(1);
    }

    @Test
    public void aServerThatDoesNotKnowTheClient_makesTheNextSignInRegisterAgain() {
        var user = signIn();
        var started = startConnecting(user);
        OAUTH.answeringCodesWithInvalidClient();
        returnToGateway(user, OAUTH.approve(started.location()), stateOf(started));

        startConnecting(user);

        assertThat(OAUTH.registrations()).hasSize(2);
    }

    @Test
    public void aServerThatListsNoTools_connectsNothing() throws Exception {
        UPSTREAM.advertising();
        var user = signIn();

        assertThat(completeSignIn(user).status()).isEqualTo(400);
        assertThat(connected(user)).isFalse();
    }

    @Test
    public void aServerThatDoesNotAcceptTheAccessItIssued_connectsNothing() throws Exception {
        OAUTH.refusingTheAccessTokensItIssues();
        var user = signIn();

        var reply = completeSignIn(user);

        assertThat(reply.status()).isEqualTo(502);
        assertThat(connected(user)).isFalse();
        assertThat(awaitInteractionsWith(user, "connect-failed")).anySatisfy(entry -> assertThat(entry.output()).contains("TOKEN_REJECTED"));
    }

    @Test
    public void aServerThatRefusesRegistration_startsNoSignIn() {
        OAUTH.refusingRegistration();

        var started = startConnecting(signIn());

        assertThat(started.status()).isEqualTo(502);
        assertThat(started.location()).isEmpty();
    }

    @Test
    public void discoveryThatFindsSomethingUnsafeOrWrong_startsNoSignIn_andRegistersNothing() {
        var setups = List.<Runnable>of(OAUTH::notOfferingPkce, OAUTH::claimingAnotherIssuer, OAUTH::withoutAuthorizationServerMetadata,
                OAUTH::describingAnotherResource, OAUTH::advertisingAPlainHttpTokenEndpoint);
        for (var setup : setups) {
            OAUTH.reset();
            setup.run();

            var started = startConnecting(signIn());

            assertThat(started.status()).isEqualTo(502);
            assertThat(OAUTH.registrations()).isEmpty();
        }
    }

    @Test
    public void anAccessTokenDueForRenewal_isRenewed_andTheCallForwardsTheNewOne() throws Exception {
        var user = userDueForRenewal();

        assertThat(isToolError(callTool(user, GET_ITEM))).isFalse();

        assertThat(OAUTH.issued()).extracting(FakeOAuthServer.Issued::grant).containsExactly("refresh_token");
        assertThat(OAUTH.tokenRequests().get(0)).containsEntry("resource", UPSTREAM.url() + "/mcp").containsEntry("client_id", "client-seeded");
        assertThat(UPSTREAM.callsTo("get_item")).extracting(ReceivedCall::authorization).containsExactly("Bearer " + OAUTH.issued().get(0).accessToken());
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void anAccessTokenWellOutsideItsRenewalMargin_isNotRenewed() throws Exception {
        var user = userWithTokens(true, Duration.ofMinutes(1), Duration.ofHours(1));

        assertThat(isToolError(callTool(user, GET_ITEM))).isFalse();

        assertThat(OAUTH.refreshGrants()).isZero();
    }

    @Test
    public void callsThatFindTheTokenDueForRenewalTogether_renewItOnce_andAllSucceed() throws Exception {
        var user = userDueForRenewal();
        OAUTH.slowToRefresh(400);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var calls = new ArrayList<Callable<JsonNode>>();
            for (var i = 0; i < 4; i++) calls.add(() -> callTool(user, GET_ITEM));

            for (var future : pool.invokeAll(calls)) assertThat(isToolError(future.get())).isFalse();
        } finally {
            pool.shutdownNow();
        }

        assertThat(OAUTH.refreshGrants()).isEqualTo(1);
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void aRefreshTokenTheServerSaysIsGone_endsTheSignIn_andRecordsWhy() throws Exception {
        var user = userDueForRenewal();
        OAUTH.refusingEveryRefresh();

        var result = callTool(user, GET_ITEM);

        assertThat(isToolError(result)).isTrue();
        assertThat(toolText(result)).contains("sign-in has ended");
        assertThat(connected(user)).isFalse();
        assertThat(UPSTREAM.callsTo("get_item")).isEmpty();
        assertThat(awaitInteractionsWith(user, "disconnect")).anySatisfy(entry -> assertThat(entry.output()).hasValueSatisfying(
                detail -> assertThat(detail).contains("authorization server refused the refresh token")));
    }

    @Test
    public void aClientTheServerDoesNotKnow_endsTheSignIn_andMakesTheNextSignInRegisterAgain() throws Exception {
        var registered = startConnecting(signIn());
        assertThat(registered.status()).isEqualTo(302);
        var user = userDueForRenewal();
        OAUTH.answeringRefreshWith(401, "invalid_client");

        assertThat(toolText(callTool(user, GET_ITEM))).contains("sign-in has ended");

        assertThat(connected(user)).isFalse();
        startConnecting(signIn());
        assertThat(OAUTH.registrations()).hasSize(2);
    }

    @Test
    public void aRefreshAnsweredWithAnyOtherError_keepsTheUserConnected_andDoesNotAskAgainAtOnce() throws Exception {
        var user = userDueForRenewal();
        OAUTH.answeringRefreshWith(400, "invalid_request");

        var first = callTool(user, GET_ITEM);
        var second = callTool(user, GET_ITEM);

        assertThat(toolText(first)).contains("temporarily unavailable");
        assertThat(toolText(second)).contains("temporarily unavailable");
        assertThat(connected(user)).isTrue();
        assertThat(OAUTH.tokenRequests()).hasSize(1);
    }

    @Test
    public void aRefreshAnswerWithoutAnAccessToken_isNotTakenForARenewal() throws Exception {
        var user = userDueForRenewal();
        OAUTH.answeringRefreshesWithoutAnAccessToken();

        assertThat(toolText(callTool(user, GET_ITEM))).contains("temporarily unavailable");
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void aTokenEndpointThatCannotBeReached_keepsTheUserConnected() throws Exception {
        var user = userDueForRenewal();
        OAUTH.withTokenEndpointDown();

        assertThat(toolText(callTool(user, GET_ITEM))).contains("temporarily unavailable");
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void whileRenewalFails_anAccessTokenThatIsStillGood_isStillUsed_andRenewalIsNotTriedAgainAtOnce() throws Exception {
        var user = userWithTokens(true, Duration.ofHours(1), Duration.ofSeconds(30));
        OAUTH.withTokenEndpointDown();

        assertThat(isToolError(callTool(user, GET_ITEM))).isFalse();
        assertThat(isToolError(callTool(user, GET_ITEM))).isFalse();

        assertThat(OAUTH.tokenRequests()).hasSize(1);
        assertThat(UPSTREAM.callsTo("get_item")).extracting(ReceivedCall::authorization).containsOnly("Bearer " + seededAccessToken);
    }

    @Test
    public void anAccessWithNoRefreshToken_countsAsNotConnectedOnceItExpires() throws Exception {
        var user = userWithTokens(false, Duration.ofHours(1), Duration.ofSeconds(-5));

        var result = callTool(user, GET_ITEM);

        assertThat(isToolError(result)).isTrue();
        assertThat(toolText(result)).contains("not connected");
        assertThat(connected(user)).isFalse();
        assertThat(UPSTREAM.callsTo("get_item")).isEmpty();
    }

    @Test
    public void aSignInThatIssuesNoRefreshToken_connects_andTheAccessIsUsed() throws Exception {
        OAUTH.issuingNoRefreshTokens();
        var user = connectedUser();

        assertThat(connected(user)).isTrue();
        assertThat(isToolError(callTool(user, GET_ITEM))).isFalse();
    }

    @Test
    public void aRotatingRefreshToken_isStored_soTheNextRenewalWorks() throws Exception {
        var user = userDueForRenewal();
        OAUTH.issuingAccessTokensValidForSeconds(1);
        assertThat(isToolError(callTool(user, GET_ITEM))).isFalse();
        Thread.sleep(700);
        OAUTH.issuingAccessTokensValidForSeconds(3600);

        assertThat(isToolError(callTool(user, GET_ITEM))).isFalse();

        assertThat(OAUTH.refreshGrants()).isEqualTo(2);
        assertThat(connected(user)).isTrue();
    }

    @Test
    public void anAccessTokenThatCannotBeRenewedForTenMinutes_endsTheSignIn() throws Exception {
        var user = userWithTokens(true, Duration.ofHours(2), Duration.ofMinutes(-11));
        OAUTH.answeringRefreshWith(400, "invalid_target");

        assertThat(toolText(callTool(user, GET_ITEM))).contains("sign-in has ended");

        assertThat(connected(user)).isFalse();
    }

    @Test
    public void anAccessWithNoRefreshToken_isUsedUntilItReallyExpires() throws Exception {
        var user = userWithTokens(false, Duration.ofHours(1), Duration.ofSeconds(30));

        assertThat(isToolError(callTool(user, GET_ITEM))).isFalse();
    }

    @Test
    public void anAccessTokenJustIssuedThatTheServerRefuses_disconnectsTheUser() throws Exception {
        var user = connectedUser();
        OAUTH.revoke(OAUTH.issued().get(0).accessToken());

        var refused = callTool(user, GET_ITEM);

        assertThat(toolText(refused)).contains("did not accept your sign-in");
        assertThat(connected(user)).isFalse();
        assertThat(OAUTH.refreshGrants()).isZero();
    }

    @Test
    public void anOlderAccessTokenTheServerRefuses_isRenewedOnTheNextCall() throws Exception {
        var user = userWithTokens(true, Duration.ofHours(1), Duration.ofHours(1));
        OAUTH.revoke(seededAccessToken);

        var refused = callTool(user, GET_ITEM);

        assertThat(toolText(refused)).contains("did not accept your sign-in");
        assertThat(connected(user)).isTrue();
        assertThat(isToolError(callTool(user, GET_ITEM))).isFalse();
        assertThat(OAUTH.refreshGrants()).isEqualTo(1);
    }

    @Test
    public void aRenewedTokenTheServerRevokesToo_disconnectsTheUser() throws Exception {
        var user = userDueForRenewal();
        var renewal = callTool(user, GET_ITEM);
        assertThat(isToolError(renewal)).as(toolText(renewal)).isFalse();
        OAUTH.revoke(OAUTH.issued().get(0).accessToken());

        assertThat(isToolError(callTool(user, GET_ITEM))).isTrue();

        assertThat(connected(user)).isFalse();
        assertThat(OAUTH.refreshGrants()).isEqualTo(1);
    }

    @Test
    public void aRefusedRecentToken_ends_thenTheNextCallIsNotConnected_andOnlyOneDisconnectIsRecorded() throws Exception {
        var user = connectedUser();
        OAUTH.revoke(OAUTH.issued().get(0).accessToken());

        callTool(user, GET_ITEM);
        callTool(user, GET_ITEM);

        awaitInteractionsWith(user, "disconnect");
        Thread.sleep(500);
        assertThat(awaitInteractionsWith(user, "disconnect")).filteredOn(entry -> entry.direction().equals("disconnect")).hasSize(1);
    }

    @Test
    public void disconnecting_forgetsTheSignIn() throws Exception {
        var user = connectedUser();

        disconnect(user);

        assertThat(connected(user)).isFalse();
        assertThat(toolText(callTool(user, GET_ITEM))).contains("not connected");
    }

    @Test
    public void theConnectionTestOnTheDashboard_saysWhenTheSignInHasEnded() throws Exception {
        var user = userDueForRenewal();
        OAUTH.refusingEveryRefresh();

        var test = httpClient.POST("/token-mcp/" + TokenMcpFixtures.ID + "/test")
                .addHeader("Cookie", "SESSION=" + user.browserSession()).addHeader("Origin", GATEWAY_ORIGIN).invoke();

        assertThat(test.body().utf8String()).contains("sign-in has ended");
    }

    @Test
    public void aUserWithoutTheOktaApp_cannotStartOrCompleteASignIn() {
        var user = signIn(List.of());

        assertThat(startConnecting(user).status()).isEqualTo(403);
        assertThat(get("/token-mcp-oauth/callback?code=abc&state=" + TokenMcpFixtures.ID + ".x", user).status()).isEqualTo(403);
        assertThat(OAUTH.registrations()).isEmpty();
        assertThat(awaitInteractionsWith(user, "connect-blocked")).anySatisfy(entry -> assertThat(entry.output()).contains("NO_APP"));
    }

    @Test
    public void startingASignInWithoutASession_goesToTheLogin_andBackToTheDashboard_whetherOrNotTheConnectorExists() {
        for (var id : List.of(TokenMcpFixtures.ID, "no-such-connector")) {
            var response = request(connectPath(id), Optional.empty(), Map.of());

            assertThat(response.status()).isEqualTo(302);
            assertThat(response.location()).isEqualTo("/login?return_to=%2F");
        }
    }

    @Test
    public void anUnknownConnector_isNotFound() {
        assertThat(get(connectPath("no-such-connector"), signIn()).status()).isEqualTo(404);
    }

    @Test
    public void theErrorPages_linkBackToTheDashboard() {
        var page = get(connectPath("no-such-connector"), signIn());

        assertThat(page.body()).contains("<a href=\"/\">Back to the dashboard</a>");
    }

    @Test
    public void pastingATokenIntoAnOAuthConnector_isRefused() throws Exception {
        var user = signIn();

        var response = httpClient.POST("/token-mcp/" + TokenMcpFixtures.ID + "/connect")
                .addHeader("Cookie", "SESSION=" + user.browserSession())
                .addHeader("Origin", GATEWAY_ORIGIN)
                .withRequestBody(new TokenMcpEndpoint.ConnectRequest("a-pasted-token"))
                .invoke();

        assertThat(response.status().intValue()).isEqualTo(400);
        assertThat(connected(user)).isFalse();
    }
}
