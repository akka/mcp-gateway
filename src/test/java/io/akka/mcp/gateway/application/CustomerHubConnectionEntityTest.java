package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import io.akka.mcp.gateway.domain.CustomerHubConnection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CustomerHubConnectionEntityTest {

    private static KeyValueEntityTestKit<CustomerHubConnection, CustomerHubConnectionEntity> testKit() {
        return KeyValueEntityTestKit.of("user@example.com", CustomerHubConnectionEntity::new);
    }

    @Test
    public void getStatus_whenEmpty_isDisconnected() {
        var result = testKit().method(CustomerHubConnectionEntity::getStatus).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply().connected()).isFalse();
    }

    @Test
    public void getToken_whenEmpty_isEmpty() {
        var result = testKit().method(CustomerHubConnectionEntity::getToken).invoke();

        assertThat(result.getReply()).isEmpty();
    }

    @Test
    public void connect_keepsTheToken_andGetTokenReturnsIt() {
        var kit = testKit();

        var connected = kit.method(CustomerHubConnectionEntity::connect).invoke("hubtoken-abc");

        assertThat(connected.isReply()).isTrue();
        assertThat(kit.method(CustomerHubConnectionEntity::getStatus).invoke().getReply().connected()).isTrue();
        assertThat(kit.method(CustomerHubConnectionEntity::getToken).invoke().getReply()).contains("hubtoken-abc");
    }

    @Test
    public void connect_withAMalformedToken_isRefusedAndKeepsNothing() {
        var kit = testKit();

        var result = kit.method(CustomerHubConnectionEntity::connect).invoke("chub abc\r\nX-Injected: 1");

        assertThat(result.isError()).isTrue();
        assertThat(kit.getState().isConnected()).isFalse();
    }

    @Test
    public void connect_again_replacesTheToken() {
        var kit = testKit();
        kit.method(CustomerHubConnectionEntity::connect).invoke("hubtoken-old");

        kit.method(CustomerHubConnectionEntity::connect).invoke("hubtoken-new");

        assertThat(kit.method(CustomerHubConnectionEntity::getToken).invoke().getReply()).contains("hubtoken-new");
    }

    @Test
    public void disconnect_forgetsTheToken() {
        var kit = testKit();
        kit.method(CustomerHubConnectionEntity::connect).invoke("hubtoken-abc");

        var result = kit.method(CustomerHubConnectionEntity::disconnect).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(kit.method(CustomerHubConnectionEntity::getStatus).invoke().getReply().connected()).isFalse();
        assertThat(kit.method(CustomerHubConnectionEntity::getToken).invoke().getReply()).isEmpty();
    }

    @Test
    public void disconnectIfToken_forgetsTheTokenWhenItIsStillTheRefusedOne() {
        var kit = testKit();
        kit.method(CustomerHubConnectionEntity::connect).invoke("hubtoken-refused");

        var result = kit.method(CustomerHubConnectionEntity::disconnectIfToken).invoke("hubtoken-refused");

        assertThat(result.getReply()).isTrue();
        assertThat(kit.getState().isConnected()).isFalse();
    }

    @Test
    public void disconnectIfToken_keepsANewerTokenPastedSinceTheRefusedOneWasRead() {
        var kit = testKit();
        kit.method(CustomerHubConnectionEntity::connect).invoke("hubtoken-refused");
        kit.method(CustomerHubConnectionEntity::connect).invoke("hubtoken-newer");

        var result = kit.method(CustomerHubConnectionEntity::disconnectIfToken).invoke("hubtoken-refused");

        assertThat(result.getReply()).isFalse();
        assertThat(kit.getState().token()).contains("hubtoken-newer");
    }

    @Test
    public void disconnectIfToken_whenNotConnected_changesNothing() {
        var kit = testKit();

        var result = kit.method(CustomerHubConnectionEntity::disconnectIfToken).invoke("hubtoken-refused");

        assertThat(result.getReply()).isFalse();
        assertThat(kit.getState().isConnected()).isFalse();
    }
}
