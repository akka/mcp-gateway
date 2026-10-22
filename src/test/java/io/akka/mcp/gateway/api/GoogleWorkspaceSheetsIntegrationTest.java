package io.akka.mcp.gateway.api;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.mcp.gateway.testsupport.GatewayFixtures;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class GoogleWorkspaceSheetsIntegrationTest extends TestKitSupport {

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withAdditionalConfig("""
                google-workspace.client-id = "test-client-id"
                google-workspace.redirect-uri = "http://localhost/googleworkspace/oauth/callback"
                """);
    }

    @Test
    public void workspaceConnect_asksGoogleForTheSpreadsheetsScope() {
        var session = GatewayFixtures.browserSession(componentClient,
                "sheets-connect-" + UUID.randomUUID() + "@lightbend.com", List.of());

        var response = httpClient.GET("/googleworkspace/oauth/connect")
                .addHeader("Cookie", "SESSION=" + session)
                .invoke();

        assertThat(response.status()).isEqualTo(StatusCodes.FOUND);
        var location = response.httpResponse().getHeader("Location").orElseThrow().value();
        var scopes = Arrays.stream(URI.create(location).getRawQuery().split("&"))
                .filter(pair -> pair.startsWith("scope="))
                .map(pair -> URLDecoder.decode(pair.substring("scope=".length()), StandardCharsets.UTF_8))
                .flatMap(value -> Arrays.stream(value.split(" ")))
                .toList();
        assertThat(scopes).contains(
                "https://www.googleapis.com/auth/spreadsheets",
                "https://www.googleapis.com/auth/documents",
                "https://www.googleapis.com/auth/drive.file");
    }
}
