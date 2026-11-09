package io.akka.mcp.gateway.application;

import akka.javasdk.client.ComponentClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CustomerHubMcpClientTest {

    private static final String APP_ID = "customer-hub-app";
    private static final String HTTPS_URL = "https://hub.example.com";
    private static final String USER = "user@example.com";

    private static CustomerHubMcpClient clientFor(String url, String appId, ComponentClient componentClient) {
        return new CustomerHubMcpClient(componentClient, null, url, appId);
    }

    private static ComponentClient failingComponentClient() {
        return (ComponentClient) Proxy.newProxyInstance(
                CustomerHubMcpClientTest.class.getClassLoader(), new Class<?>[] {ComponentClient.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("the entity is down");
                });
    }

    @Test
    public void isAvailable_needsBothAnHttpsOrLoopbackUrlAndAnOktaApp() {
        assertThat(clientFor(HTTPS_URL, APP_ID, null).isAvailable()).isTrue();
        assertThat(clientFor("http://127.0.0.1:9000", APP_ID, null).isAvailable()).isTrue();
        assertThat(clientFor("http://localhost:9000", APP_ID, null).isAvailable()).isTrue();
        assertThat(clientFor("http://[::1]:9000", APP_ID, null).isAvailable()).isTrue();

        assertThat(clientFor("", APP_ID, null).isAvailable()).isFalse();
        assertThat(clientFor(HTTPS_URL, "", null).isAvailable()).isFalse();
        assertThat(clientFor("http://hub.example.com", APP_ID, null).isAvailable()).isFalse();
        assertThat(clientFor("not a url", APP_ID, null).isAvailable()).isFalse();
    }

    @Test
    public void isAvailable_isFalseForAUrlWithoutAHost() {
        assertThat(clientFor("https:/hub", APP_ID, null).isAvailable()).isFalse();
        assertThat(clientFor("https:///hub", APP_ID, null).isAvailable()).isFalse();
        assertThat(clientFor("//hub.example.com", APP_ID, null).isAvailable()).isFalse();
    }

    @Test
    public void isConnected_isFalseWhenNotAvailable_withoutAskingAnyEntity() {
        assertThat(clientFor("", "", failingComponentClient()).isConnected(USER)).isFalse();
    }

    @Test
    public void isConnected_isFalseWhenTheEntityCallFails() {
        assertThat(clientFor(HTTPS_URL, APP_ID, failingComponentClient()).isConnected(USER)).isFalse();
    }

    @Test
    public void callTool_whenTheTokenLookupFails_saysTheRequestFailed_withoutTheEntitysWords() {
        var result = clientFor(HTTPS_URL, APP_ID, failingComponentClient()).callTool(USER, "CustomerHub_get_request", Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(result.text()).contains("request failed").doesNotContain("the entity is down");
    }

    @Test
    public void listToolsWith_whenNotAvailable_isRefused() {
        var client = clientFor("http://hub.example.com", APP_ID, null);

        assertThatThrownBy(() -> client.listToolsWith("hubtoken-abc")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void howTo_pointsToTheDashboard_andSaysWhatIsKept() {
        var howTo = clientFor(HTTPS_URL, APP_ID, null).howTo("https://gateway.example.com");

        assertThat(howTo.markdownBody()).contains("https://gateway.example.com").contains("CustomerHub_").contains("stores your token");
    }

    @Test
    public void canHandle_onlyToolsWithTheHubPrefix() {
        var client = clientFor(HTTPS_URL, APP_ID, null);

        assertThat(client.canHandle("CustomerHub_get_request")).isTrue();
        assertThat(client.canHandle("slack_post_message")).isFalse();
        assertThat(client.canHandle(null)).isFalse();
    }
}
