package io.akka.mcp.gateway.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Refreshing an MCP token re-reads the user's Okta app assignments. An app that is hidden from the
 * user's dashboard is still an assignment, and a failed lookup must not revoke or invent any.
 */
public class OktaAppsRefreshIntegrationTest extends TestKitSupport {

    private static final String EMAIL = "user@lightbend.com";
    private static final String CLIENT_ID = "refresh-test-client";
    private static final UserSession.App STORED_APP = new UserSession.App("0oa-stored", "Stored");
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
                """.formatted(OKTA.url()));
    }

    @BeforeEach
    public void signedInUserExistsInOkta() {
        OKTA.reset()
                .respond("/api/v1/users/" + EMAIL, 200, "{\"id\":\"00u1\"}")
                .respond("/api/v1/users/" + EMAIL + "/groups", 200, "[]");
    }

    private List<UserSession.App> appsAfterRefreshing() throws Exception {
        var refreshToken = UUID.randomUUID().toString();
        componentClient.forKeyValueEntity(refreshToken)
                .method(OAuthRefreshTokenEntity::create)
                .invoke(new OAuthRefreshTokenEntity.CreateCommand(
                        refreshToken, EMAIL, "User", CLIENT_ID, List.of(),
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

        var newRefreshToken = MAPPER.readTree(response.body()).path("refresh_token").asText();
        return componentClient.forKeyValueEntity(newRefreshToken)
                .method(OAuthRefreshTokenEntity::get)
                .invoke()
                .apps();
    }

    @Test
    public void refresh_replacesStoredAppsWithTheAssignedActiveOnesIncludingHiddenApps() throws Exception {
        OKTA.respond("/api/v1/apps", 200, """
                [{"id":"0oa-hidden","label":"Seamless","status":"ACTIVE"},
                 {"id":"0oa-retired","label":"Retired","status":"INACTIVE"}]""");

        assertThat(appsAfterRefreshing()).containsExactly(HIDDEN_APP);
    }

    @Test
    public void refresh_keepsStoredAppsWhenOktaRefusesTheLookup() throws Exception {
        OKTA.respond("/api/v1/apps", 403, "{}");

        assertThat(appsAfterRefreshing()).containsExactly(STORED_APP);
    }
}
