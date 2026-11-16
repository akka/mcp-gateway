package io.akka.mcp.gateway.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.mcp.gateway.application.OAuthTokenConnectionEntity;
import io.akka.mcp.gateway.application.TokenConnectionEntity;
import io.akka.mcp.gateway.domain.OAuthTokenConnection.Tokens;
import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.testsupport.FakeMcpServer;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.AdvertisedTool;
import io.akka.mcp.gateway.testsupport.FakeOAuthServer;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import io.akka.mcp.gateway.testsupport.TokenMcpFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every tool of an OAuth connector stays listed whatever the caller's connection, once anyone has connected, even when
 * the caller's own sign-in cannot be renewed. This class owns its tool cache, so it starts cold.
 */
public class TokenMcpOAuthToolListIntegrationTest extends TestKitSupport {

    private static final String READER_GROUP = "mcp-gateway-reader";
    private static final String APP_ID = "oauth-tool-list-app-id";
    private static final String GET_ITEM = TokenMcpFixtures.TOOL_PREFIX + "get_item";
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
                okta.groups.reader = "%s"
                """.formatted(TokenMcpFixtures.oauthSetting(UPSTREAM.url(), APP_ID), READER_GROUP));
    }

    private record Caller(String email, String mcpToken) {}

    private Caller callerWithTokens(Optional<Duration> expiresIn) {
        var email = "oauth-list-" + UUID.randomUUID() + "@lightbend.com";
        var apps = List.of(new UserSession.App(APP_ID, TokenMcpFixtures.NAME));
        var caller = new Caller(email, GatewayFixtures.mcpToken(componentClient, email, List.of(READER_GROUP), apps));
        expiresIn.ifPresent(lifetime -> {
            var direct = OAUTH.issueDirectly();
            var now = Instant.now();
            componentClient.forKeyValueEntity(TokenConnectionEntity.keyFor(TokenMcpFixtures.ID, email)).method(OAuthTokenConnectionEntity::connect)
                    .invoke(Tokens.issued(direct.accessToken(), Optional.of(direct.refreshToken()), now.minus(Duration.ofHours(1)), now.plus(lifetime),
                            "client-seeded", UPSTREAM.url() + "/oauth2/token", UPSTREAM.url() + "/mcp"));
        });
        return caller;
    }

    private List<String> listedTools(Caller caller) throws Exception {
        var names = new ArrayList<String>();
        var response = httpClient.POST("/mcp").addHeader("Authorization", "Bearer " + caller.mcpToken())
                .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list")).responseBodyAs(String.class).invoke();
        MAPPER.readTree(response.body()).path("result").path("tools").forEach(tool -> names.add(tool.path("name").asText()));
        return names;
    }

    @Test
    public void theToolsOfAConnector_stayListed_forEveryoneOnceAnyoneHasConnected_evenWhenACallersRenewalFails() throws Exception {
        var neverConnected = callerWithTokens(Optional.empty());
        var failing = callerWithTokens(Optional.of(Duration.ofSeconds(-5)));
        OAUTH.refusingEveryRefresh();
        assertThat(listedTools(neverConnected)).as("nothing is cached before anyone connects").doesNotContain(GET_ITEM);
        assertThat(listedTools(failing)).as("a failed renewal is not an error and lists nothing new").doesNotContain(GET_ITEM);

        OAUTH.reset();
        var connected = callerWithTokens(Optional.of(Duration.ofHours(1)));
        assertThat(listedTools(connected)).contains(GET_ITEM);

        OAUTH.refusingEveryRefresh();
        var failingAgain = callerWithTokens(Optional.of(Duration.ofSeconds(-5)));
        assertThat(listedTools(neverConnected)).contains(GET_ITEM);
        assertThat(listedTools(failing)).contains(GET_ITEM);
        assertThat(listedTools(failingAgain)).contains(GET_ITEM);
        assertThat(OAUTH.tokenRequests()).as("a cached list needs no renewal").isEmpty();
    }
}
