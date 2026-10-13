package io.akka.mcp.gateway.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.mcp.gateway.application.McpAccessTokenEntity;
import io.akka.mcp.gateway.application.OAuthRefreshTokenEntity;
import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.testsupport.FakeOkta;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Refreshing an MCP token re-reads the user's Okta group membership. */
public class OktaGroupsRefreshIntegrationTest extends TestKitSupport {

    private static final String EMAIL = "user@lightbend.com";
    private static final String CLIENT_ID = "groups-refresh-test-client";
    private static final String STORED_GROUP = "mcp-gateway-writer";
    private static final String GROUPS_PATH = "/api/v1/users/" + EMAIL + "/groups";
    private static final UserSession.App STORED_APP = new UserSession.App("0oa-stored", "Stored");

    private static final FakeOkta OKTA = FakeOkta.start();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterAll
    public static void stopFakeOkta() {
        OKTA.close();
    }

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                okta.issuer-url = "%s"
                okta.api-token = "test-api-token"
                """.formatted(OKTA.url()));
    }

    /** The apps lookup is answered throughout, so only the groups lookup is under test. */
    @BeforeEach
    public void signedInUserExistsInOkta() {
        OKTA.reset()
                .respond("/api/v1/users/" + EMAIL, 200, "{\"id\":\"00u1\"}")
                .respond("/api/v1/apps", 200, "[]");
    }

    private record Refreshed(int status, List<String> groups, String accessToken) {}

    private String storedRefreshToken() {
        var refreshToken = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(refreshToken)
                .method(OAuthRefreshTokenEntity::create)
                .invoke(new OAuthRefreshTokenEntity.CreateCommand(
                        refreshToken, EMAIL, "User", CLIENT_ID, List.of(STORED_GROUP),
                        Instant.now().plusSeconds(3600), List.of(STORED_APP), ""));
        return refreshToken;
    }

    private CompletableFuture<HttpResponse<String>> refreshing(String refreshToken) {
        return HTTP.sendAsync(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + testKit.getPort() + "/oauth2/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=refresh_token&refresh_token=" + refreshToken + "&client_id=" + CLIENT_ID))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Refreshed refreshed() throws Exception {
        var response = refreshing(storedRefreshToken()).get(30, TimeUnit.SECONDS);

        var body = MAPPER.readTree(response.body());
        var newRefreshToken = body.path("refresh_token").asText();
        var groups = componentClient.forKeyValueEntity(newRefreshToken)
                .method(OAuthRefreshTokenEntity::get)
                .invoke()
                .groups();
        return new Refreshed(response.statusCode(), groups, body.path("access_token").asText());
    }

    private boolean isValid(String refreshToken) {
        return componentClient.forKeyValueEntity(refreshToken)
                .method(OAuthRefreshTokenEntity::get)
                .invoke()
                .isValid();
    }

    @Test
    public void refresh_replacesStoredGroupsWithTheCurrentOktaMembership() throws Exception {
        OKTA.respond(GROUPS_PATH, 200, """
                [{"profile":{"name":"mcp-gateway-reader"}},
                 {"profile":{"name":"mcp-gateway-admin"}}]""");

        assertThat(refreshed().groups()).containsExactly("mcp-gateway-reader", "mcp-gateway-admin");
    }

    @Test
    public void refresh_dropsStoredGroupsWhenOktaRefusesTheLookup() throws Exception {
        OKTA.respond(GROUPS_PATH, 403, "{}");

        assertThat(refreshed().groups()).isEmpty();
    }

    @Test
    public void refresh_dropsStoredGroupsWhenOktaAnswersWithSomethingOtherThanAnArray() throws Exception {
        OKTA.respond(GROUPS_PATH, 200, "{\"errorCode\":\"E0000007\"}");

        assertThat(refreshed().groups()).isEmpty();
    }

    @Test
    public void refresh_stillIssuesAUsableTokenWhenTheGroupsLookupFails() throws Exception {
        OKTA.respond(GROUPS_PATH, 500, "{}");

        var refreshed = refreshed();
        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(refreshed.accessToken()).isNotBlank();

        var accessToken = componentClient.forKeyValueEntity(refreshed.accessToken())
                .method(McpAccessTokenEntity::get)
                .invoke();
        assertThat(accessToken.isEmpty()).isFalse();
        assertThat(accessToken.isExpired()).isFalse();
        assertThat(accessToken.userId()).isEqualTo(EMAIL);
        assertThat(accessToken.groups()).isEmpty();
    }

    @Test
    public void refresh_keepsTheOldRefreshTokenValidUntilTheOktaLookupsAnswer() throws Exception {
        OKTA.respond(GROUPS_PATH, 200, "[]").hold(GROUPS_PATH);
        var refreshToken = storedRefreshToken();

        var response = refreshing(refreshToken);
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() ->
                OKTA.requests().stream().anyMatch(r -> r.pathAndQuery().startsWith(GROUPS_PATH)));

        assertThat(isValid(refreshToken)).isTrue();
        OKTA.release();
        assertThat(response.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(isValid(refreshToken)).isFalse();
    }
}
