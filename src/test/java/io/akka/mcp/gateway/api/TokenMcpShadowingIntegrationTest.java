package io.akka.mcp.gateway.api;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.domain.UserSession;
import io.akka.mcp.gateway.testsupport.FakeMcpServer;
import io.akka.mcp.gateway.testsupport.FakeMcpServer.AdvertisedTool;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A connector can never list a tool name that a built-in system already answers to. */
public class TokenMcpShadowingIntegrationTest extends TestKitSupport {

    private static final String GATEWAY_ORIGIN = "http://localhost:9000";
    private static final String APP_ID = "shadowing-app-under-test";
    private static final FakeMcpServer UPSTREAM = FakeMcpServer.start().advertising(
            new AdvertisedTool("GoogleDocs_search", true),
            new AdvertisedTool("other_tool", true));

    @AfterAll
    public static void stopUpstream() {
        UPSTREAM.close();
    }

    @Override
    protected TestKit.Settings testKitSettings() {
        var entry = """
                {"id":"shadowing-service","name":"Shadowing Service","tool-prefix":"Workspace_","mcp-url":"%s","okta-app-id":"%s"}"""
                .formatted(UPSTREAM.url(), APP_ID);
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                token-mcps = \"\"\"[%s]\"\"\"
                mcp.base-url = "%s"
                """.formatted(entry, GATEWAY_ORIGIN));
    }

    @Test
    public void aToolNameABuiltInSystemAnswersTo_isLeftOutOfTheConnectorsTools() {
        var token = "token-" + UUID.randomUUID();
        UPSTREAM.acceptingOnlyBearer(token);
        var email = "shadowing-" + UUID.randomUUID() + "@lightbend.com";
        var session = GatewayFixtures.browserSession(componentClient, email, List.of(),
                List.of(new UserSession.App(APP_ID, "Shadowing Service")));

        var response = httpClient.POST("/token-mcp/shadowing-service/connect")
                .addHeader("Cookie", "SESSION=" + session)
                .addHeader("Origin", GATEWAY_ORIGIN)
                .withRequestBody(new TokenMcpEndpoint.ConnectRequest(token))
                .invoke();

        assertThat(response.status().intValue()).isEqualTo(200);
        assertThat(response.body().utf8String()).contains("Workspace_other_tool").doesNotContain("Workspace_GoogleDocs_search");
    }
}
