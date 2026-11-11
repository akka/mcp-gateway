package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import io.akka.mcp.gateway.domain.TokenConnection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TokenConnectionEntityTest {

    private static KeyValueEntityTestKit<TokenConnection, TokenConnectionEntity> testKit() {
        return KeyValueEntityTestKit.of(TokenConnectionEntity.keyFor("test-token-service", "user@example.com"), TokenConnectionEntity::new);
    }

    @Test
    public void getStatus_whenEmpty_isDisconnected() {
        var result = testKit().method(TokenConnectionEntity::getStatus).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(result.getReply().connected()).isFalse();
    }

    @Test
    public void getToken_whenEmpty_isEmpty() {
        var result = testKit().method(TokenConnectionEntity::getToken).invoke();

        assertThat(result.getReply()).isEmpty();
    }

    @Test
    public void connect_keepsTheToken_andGetTokenReturnsIt() {
        var kit = testKit();

        var connected = kit.method(TokenConnectionEntity::connect).invoke("upstreamtoken-abc");

        assertThat(connected.isReply()).isTrue();
        assertThat(kit.method(TokenConnectionEntity::getStatus).invoke().getReply().connected()).isTrue();
        assertThat(kit.method(TokenConnectionEntity::getToken).invoke().getReply()).contains("upstreamtoken-abc");
    }

    @Test
    public void connect_withAMalformedToken_isRefusedAndKeepsNothing() {
        var kit = testKit();

        var result = kit.method(TokenConnectionEntity::connect).invoke("tokenabc\r\nX-Injected: 1");

        assertThat(result.isError()).isTrue();
        assertThat(kit.getState().isConnected()).isFalse();
    }

    @Test
    public void connect_again_replacesTheToken() {
        var kit = testKit();
        kit.method(TokenConnectionEntity::connect).invoke("upstreamtoken-old");

        kit.method(TokenConnectionEntity::connect).invoke("upstreamtoken-new");

        assertThat(kit.method(TokenConnectionEntity::getToken).invoke().getReply()).contains("upstreamtoken-new");
    }

    @Test
    public void disconnect_forgetsTheToken() {
        var kit = testKit();
        kit.method(TokenConnectionEntity::connect).invoke("upstreamtoken-abc");

        var result = kit.method(TokenConnectionEntity::disconnect).invoke();

        assertThat(result.isReply()).isTrue();
        assertThat(kit.method(TokenConnectionEntity::getStatus).invoke().getReply().connected()).isFalse();
        assertThat(kit.method(TokenConnectionEntity::getToken).invoke().getReply()).isEmpty();
    }

    @Test
    public void disconnectIfToken_forgetsTheTokenWhenItIsStillTheRefusedOne() {
        var kit = testKit();
        kit.method(TokenConnectionEntity::connect).invoke("upstreamtoken-refused");

        var result = kit.method(TokenConnectionEntity::disconnectIfToken).invoke("upstreamtoken-refused");

        assertThat(result.getReply()).isTrue();
        assertThat(kit.getState().isConnected()).isFalse();
    }

    @Test
    public void disconnectIfToken_keepsANewerTokenPastedSinceTheRefusedOneWasRead() {
        var kit = testKit();
        kit.method(TokenConnectionEntity::connect).invoke("upstreamtoken-refused");
        kit.method(TokenConnectionEntity::connect).invoke("upstreamtoken-newer");

        var result = kit.method(TokenConnectionEntity::disconnectIfToken).invoke("upstreamtoken-refused");

        assertThat(result.getReply()).isFalse();
        assertThat(kit.getState().token()).contains("upstreamtoken-newer");
    }

    @Test
    public void disconnectIfToken_whenNotConnected_changesNothing() {
        var kit = testKit();

        var result = kit.method(TokenConnectionEntity::disconnectIfToken).invoke("upstreamtoken-refused");

        assertThat(result.getReply()).isFalse();
        assertThat(kit.getState().isConnected()).isFalse();
    }
}
