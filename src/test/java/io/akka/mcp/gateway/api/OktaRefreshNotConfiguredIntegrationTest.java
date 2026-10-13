package io.akka.mcp.gateway.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.mcp.gateway.application.OAuthRefreshTokenEntity;
import io.akka.mcp.gateway.domain.UserSession;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class OktaRefreshNotConfiguredIntegrationTest extends TestKitSupport {

    private static final String EMAIL = "user@lightbend.com";
    private static final String CLIENT_ID = "not-configured-refresh-test-client";
    private static final String STORED_GROUP = "mcp-gateway-writer";
    private static final UserSession.App STORED_APP = new UserSession.App("0oa-stored", "Stored");

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                okta.api-token = ""
                """);
    }

    @Test
    public void refresh_keepsStoredGroupsAndAppsWhenTheOktaLookupIsNotConfigured() throws Exception {
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
        assertThat(response.statusCode()).isEqualTo(200);

        var body = MAPPER.readTree(response.body());
        var renewed = componentClient.forKeyValueEntity(body.path("refresh_token").asText())
                .method(OAuthRefreshTokenEntity::get)
                .invoke();
        assertThat(renewed.groups()).containsExactly(STORED_GROUP);
        assertThat(renewed.apps()).containsExactly(STORED_APP);
        assertThat(body.path("expires_in").asLong()).isEqualTo(3600);
    }
}
