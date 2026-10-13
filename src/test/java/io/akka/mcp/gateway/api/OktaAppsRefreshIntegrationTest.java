package io.akka.mcp.gateway.api;

import akka.javasdk.JsonSupport;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.mcp.gateway.application.McpAccessTokenEntity;
import io.akka.mcp.gateway.application.McpRegistryEntity;
import io.akka.mcp.gateway.application.OAuthRefreshTokenEntity;
import io.akka.mcp.gateway.domain.McpAccessToken;
import io.akka.mcp.gateway.domain.McpConfig;
import io.akka.mcp.gateway.domain.OAuthRefreshToken;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refreshing an MCP token re-reads the user's Okta app assignments. An app that is hidden from the
 * user's dashboard is still an assignment. MCP clients list tools once per session, so a failed
 * lookup must not shrink the list; it refuses calls instead.
 */
public class OktaAppsRefreshIntegrationTest extends TestKitSupport {

    private static final String EMAIL = "user@lightbend.com";
    private static final String CLIENT_ID = "refresh-test-client";
    private static final String READER_GROUP = "mcp-gateway-reader";
    private static final String GROUPS_PATH = "/api/v1/users/" + EMAIL + "/groups";
    private static final String OKTA_ADMIN_TOOL = "okta_list_users";
    private static final UserSession.App STORED_APP = new UserSession.App("0oa-stored", "Okta MCP Admin");
    private static final UserSession.App HIDDEN_APP = new UserSession.App("0oa-hidden", "Seamless");

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
                okta-admin.okta-app-id = "%s"
                okta.groups.reader = "%s"
                """.formatted(OKTA.url(), STORED_APP.id(), READER_GROUP));
    }

    @BeforeEach
    public void signedInUserExistsInOkta() {
        OKTA.reset()
                .respond("/api/v1/users/" + EMAIL, 200, "{\"id\":\"00u1\"}")
                .respond(GROUPS_PATH, 200, "[{\"profile\":{\"name\":\"" + READER_GROUP + "\"}}]")
                .respond("/api/v1/apps", 200, "[{\"id\":\"0oa-stored\",\"label\":\"Okta MCP Admin\",\"status\":\"ACTIVE\"}]");
        componentClient.forKeyValueEntity(McpRegistryEntity.ENTITY_ID)
                .method(McpRegistryEntity::register)
                .invoke(new McpConfig("okta-admin", "Okta", List.of(new McpConfig.ToolMeta(
                        OKTA_ADMIN_TOOL, "List Okta users",
                        Map.of("type", "object", "properties", Map.of()), true, false))));
    }

    private record Refreshed(String accessToken, long expiresIn, OAuthRefreshToken refreshToken) {
        List<UserSession.App> apps() {
            return refreshToken.apps();
        }
    }

    private Refreshed refreshed() throws Exception {
        var refreshToken = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(refreshToken)
                .method(OAuthRefreshTokenEntity::create)
                .invoke(new OAuthRefreshTokenEntity.CreateCommand(
                        refreshToken, EMAIL, "User", CLIENT_ID, List.of(READER_GROUP),
                        Instant.now().plusSeconds(3600), List.of(STORED_APP), ""));

        var response = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + testKit.getPort() + "/oauth2/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=refresh_token&refresh_token=" + refreshToken + "&client_id=" + CLIENT_ID))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        var body = MAPPER.readTree(response.body());
        var newRefreshToken = componentClient.forKeyValueEntity(body.path("refresh_token").asText())
                .method(OAuthRefreshTokenEntity::get)
                .invoke();
        return new Refreshed(body.path("access_token").asText(), body.path("expires_in").asLong(), newRefreshToken);
    }

    private McpAccessToken accessToken(Refreshed refreshed) {
        return componentClient.forKeyValueEntity(refreshed.accessToken())
                .method(McpAccessTokenEntity::get)
                .invoke();
    }

    private List<String> listedTools(String accessToken) throws Exception {
        var response = httpClient.POST("/mcp")
                .addHeader("Authorization", "Bearer " + accessToken)
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .responseBodyAs(String.class)
                .invoke();
        var names = new ArrayList<String>();
        JsonSupport.getObjectMapper().readTree(response.body()).path("result").path("tools")
                .forEach(t -> names.add(t.path("name").asText()));
        return names;
    }

    @Test
    public void refresh_replacesStoredAppsWithTheAssignedActiveOnesIncludingHiddenApps() throws Exception {
        OKTA.respond("/api/v1/apps", 200, """
                [{"id":"0oa-hidden","label":"Seamless","status":"ACTIVE"},
                 {"id":"0oa-retired","label":"Retired","status":"INACTIVE"}]""");

        assertThat(refreshed().apps()).containsExactly(HIDDEN_APP);
    }

    @Test
    public void refresh_keepsStoredAppsButDropsGroupsWhenOktaRefusesTheAppsLookup() throws Exception {
        OKTA.respond("/api/v1/apps", 403, "{}");

        var refreshed = refreshed();
        assertThat(refreshed.apps()).containsExactly(STORED_APP);
        assertThat(refreshed.refreshToken().groups()).isEmpty();
        assertThat(accessToken(refreshed).apps()).containsExactly(STORED_APP);
        assertThat(accessToken(refreshed).groups()).isEmpty();
    }

    @Test
    public void refresh_issuesAShortLivedAccessTokenWhenALookupFails() throws Exception {
        OKTA.respond("/api/v1/apps", 500, "{}");

        var refreshed = refreshed();
        assertThat(refreshed.expiresIn()).isEqualTo(60);
        assertThat(accessToken(refreshed).expiresAt()).isBefore(Instant.now().plusSeconds(61));
    }

    @Test
    public void refresh_issuesAnHourLongAccessTokenWhenTheLookupsSucceed() throws Exception {
        assertThat(refreshed().expiresIn()).isEqualTo(3600);
    }

    @Test
    public void toolsList_staysCompleteAfterARefreshWhoseAppsLookupFailed() throws Exception {
        var before = listedTools(refreshed().accessToken());
        OKTA.respond("/api/v1/apps", 503, "{}");

        var refreshed = refreshed();

        assertThat(before).contains(OKTA_ADMIN_TOOL);
        assertThat(listedTools(refreshed.accessToken())).containsExactlyInAnyOrderElementsOf(before);
        assertThat(accessToken(refreshed).groups()).isEmpty();
    }

    @Test
    public void toolsList_staysCompleteAfterARefreshWhoseGroupsLookupFailed() throws Exception {
        var before = listedTools(refreshed().accessToken());
        OKTA.respond(GROUPS_PATH, 503, "{}");

        var refreshed = refreshed();

        assertThat(before).contains(OKTA_ADMIN_TOOL);
        assertThat(listedTools(refreshed.accessToken())).containsExactlyInAnyOrderElementsOf(before);
        assertThat(accessToken(refreshed).groups()).isEmpty();
    }
}
