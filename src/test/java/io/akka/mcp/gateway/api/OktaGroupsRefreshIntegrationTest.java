package io.akka.mcp.gateway.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.mcp.gateway.application.McpAccessTokenEntity;
import io.akka.mcp.gateway.application.OAuthRefreshTokenEntity;
import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.testsupport.FakeOkta;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refreshing an MCP token re-reads the user's Okta group membership. A lookup that runs but fails
 * leaves the user with no groups rather than the ones the old token carried: keeping those would
 * hold a since-revoked role open for as long as the failures last, and every refresh would renew
 * it (issue #112). The refresh itself still succeeds, so the client keeps a usable token and is
 * refused tool calls until a later lookup succeeds.
 */
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

    private Refreshed refreshed() throws Exception {
        var refreshToken = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(refreshToken)
                .method(OAuthRefreshTokenEntity::create)
                .invoke(new OAuthRefreshTokenEntity.CreateCommand(
                        refreshToken, EMAIL, "User", CLIENT_ID, List.of(STORED_GROUP),
                        Instant.now().plusSeconds(3600), List.of(STORED_APP), ""));

        var response = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + testKit.getPort() + "/oauth2/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=refresh_token&refresh_token=" + refreshToken + "&client_id=" + CLIENT_ID))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        var body = MAPPER.readTree(response.body());
        var newRefreshToken = body.path("refresh_token").asText();
        var groups = componentClient.forKeyValueEntity(newRefreshToken)
                .method(OAuthRefreshTokenEntity::get)
                .invoke()
                .groups();
        return new Refreshed(response.statusCode(), groups, body.path("access_token").asText());
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

    /**
     * The point of failing closed rather than failing the grant: the client is not locked out, it
     * just holds a token with no roles, so it recovers on the next refresh that reaches Okta.
     */
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
}
