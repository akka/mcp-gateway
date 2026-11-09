package io.akka.mcp.gateway.application;

import akka.javasdk.client.ComponentClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.mcp.gateway.testsupport.TokenMcpFixtures;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TokenMcpClientTest {

    private static final String APP_ID = "token-service-app";
    private static final String HTTPS_URL = "https://upstream.example.com";
    private static final String USER = "user@example.com";

    private static TokenMcpClient clientFor(String url, String appId, ComponentClient componentClient) {
        return clientFor(url, appId, false, componentClient);
    }

    private static TokenMcpClient clientFor(String url, String appId, boolean recordToolContent, ComponentClient componentClient) {
        var config = new TokenMcpConfig(TokenMcpFixtures.ID, TokenMcpFixtures.NAME, TokenMcpFixtures.TOOL_PREFIX, url, appId,
                recordToolContent, Optional.empty());
        return new TokenMcpClient(componentClient, null, config, new PastedTokenSource(componentClient, TokenMcpFixtures.ID), name -> false);
    }

    private static ComponentClient failingComponentClient() {
        return (ComponentClient) Proxy.newProxyInstance(
                TokenMcpClientTest.class.getClassLoader(), new Class<?>[] {ComponentClient.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("the entity is down");
                });
    }

    @Test
    public void isAvailable_followsTheConfig() {
        assertThat(clientFor(HTTPS_URL, APP_ID, null).isAvailable()).isTrue();
        assertThat(clientFor("http://upstream.example.com", APP_ID, null).isAvailable()).isFalse();
        assertThat(clientFor(HTTPS_URL, "", null).isAvailable()).isFalse();
    }

    @Test
    public void isConnected_isFalseWhenTheEntityCallFails() {
        assertThat(clientFor(HTTPS_URL, APP_ID, failingComponentClient()).isConnected(USER)).isFalse();
    }

    @Test
    public void connection_whenNotAvailable_isNotConnected_withoutAskingAnyEntity() {
        assertThat(clientFor("", "", failingComponentClient()).connection(USER)).isEqualTo(RemoteMcpClient.Connection.NOT_CONNECTED);
    }

    @Test
    public void connection_whenTheEntityCallFails_isACheckFailure_notNotConnected() {
        assertThat(clientFor(HTTPS_URL, APP_ID, failingComponentClient()).connection(USER)).isEqualTo(RemoteMcpClient.Connection.CHECK_FAILED);
    }

    @Test
    public void callTool_whenTheTokenLookupFails_saysTheRequestFailed_withoutTheEntitysWords() {
        var result = clientFor(HTTPS_URL, APP_ID, failingComponentClient()).callTool(USER, "TestToken_get_item", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("request failed").doesNotContain("the entity is down");
    }

    @Test
    public void listToolsWith_whenNotAvailable_isRefused() {
        var client = clientFor("http://upstream.example.com", APP_ID, null);

        assertThatThrownBy(() -> client.listToolsWith("upstreamtoken-abc")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void howTo_pointsToTheDashboard_andSaysWhatIsKept() {
        var howTo = clientFor(HTTPS_URL, APP_ID, null).howTo("https://gateway.example.com");

        assertThat(howTo.markdownBody()).contains("https://gateway.example.com").contains(TokenMcpFixtures.TOOL_PREFIX).contains("stores your token");
    }

    @Test
    public void howTo_usesTheConnectorsOwnNameAndHint() {
        var config = new TokenMcpConfig("other-service", "Other Service", "Other_", HTTPS_URL, APP_ID,
                false, Optional.of("Open the Other Service profile and create a key."));
        var client = new TokenMcpClient(null, null, config, new PastedTokenSource(null, "other-service"), name -> false);

        var body = client.howTo("https://gateway.example.com").markdownBody();

        assertThat(body).contains("How to Connect Other Service").contains("Open the Other Service profile and create a key.")
                .contains("`Other_`").doesNotContain(TokenMcpFixtures.NAME);
    }

    @Test
    public void recordsToolContent_followsTheConnectorsSetting() {
        assertThat(clientFor(HTTPS_URL, APP_ID, false, null).recordsToolContent()).isFalse();
        assertThat(clientFor(HTTPS_URL, APP_ID, true, null).recordsToolContent()).isTrue();
    }

    @Test
    public void howTo_saysWhetherTheArgumentsAndResultsAreKept() {
        assertThat(clientFor(HTTPS_URL, APP_ID, false, null).howTo("https://g.example.com").markdownBody())
                .contains("keeps no arguments or results");
        assertThat(clientFor(HTTPS_URL, APP_ID, true, null).howTo("https://g.example.com").markdownBody())
                .contains("keeps the arguments and results of your calls");
    }

    @Test
    public void aToolWithAnUnusualNameOrAnOversizedDefinition_isLeftOut() {
        assertThat(acceptable("TestToken_get_item", "d")).isTrue();
        var overhead = definitionLength("TestToken_get_item", "");
        assertThat(acceptable("TestToken_get_item", "d".repeat(8_000 - overhead))).isTrue();
        assertThat(acceptable("TestToken_get_item", "d".repeat(8_000 - overhead + 1))).isFalse();
        assertThat(acceptable("n".repeat(128), "d")).isTrue();
        assertThat(acceptable("n".repeat(129), "d")).isFalse();
        assertThat(acceptable("", "d")).isFalse();
        assertThat(acceptable("get item", "d")).isFalse();
        assertThat(acceptable("get\u00e9", "d")).isFalse();
    }

    private static int definitionLength(String name, String description) {
        try {
            return new ObjectMapper().writeValueAsString(Map.of("name", name, "description", description)).length();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean acceptable(String name, String description) {
        return TokenMcpClient.isAcceptableTool(new RemoteMcpClient.ToolEntry(Map.of("name", name, "description", description), null));
    }

    @Test
    public void canHandle_onlyToolsWithTheUpstreamPrefix() {
        var client = clientFor(HTTPS_URL, APP_ID, null);

        assertThat(client.canHandle("TestToken_get_item")).isTrue();
        assertThat(client.canHandle("slack_post_message")).isFalse();
        assertThat(client.canHandle(null)).isFalse();
    }
}
