package io.akka.mcp.gateway.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class TokenConnectionTest {

    @Test
    public void emptyConnection_isNotConnected() {
        assertThat(TokenConnection.empty().isConnected()).isFalse();
    }

    @Test
    public void withToken_isConnected_andDisconnectedForgetsIt() {
        var connected = TokenConnection.empty().withToken("upstreamtoken-abc");

        assertThat(connected.isConnected()).isTrue();
        assertThat(connected.token()).contains("upstreamtoken-abc");
        assertThat(connected.disconnected().isConnected()).isFalse();
        assertThat(connected.disconnected().token()).isEmpty();
    }

    @Test
    public void toString_neverShowsTheToken() {
        var connected = TokenConnection.empty().withToken("upstreamtoken-super-secret");

        assertThat(connected.toString()).doesNotContain("super-secret");
    }

    @Test
    public void isWellFormedToken_printableAscii_isAccepted() {
        assertThat(TokenConnection.isWellFormedToken("upstreamtoken-Abc-123._~+/=")).isTrue();
    }

    @Test
    public void isWellFormedToken_whitespaceOrControlCharacters_isRefused() {
        assertThat(TokenConnection.isWellFormedToken("upstreamtoken abc")).isFalse();
        assertThat(TokenConnection.isWellFormedToken("upstreamtoken\tabc")).isFalse();
        assertThat(TokenConnection.isWellFormedToken("upstreamtoken\u007fabc")).isFalse();
        assertThat(TokenConnection.isWellFormedToken("upstreamtoken\r\nX-Injected: 1")).isFalse();
        assertThat(TokenConnection.isWellFormedToken("upstreamtoken\u0000abc")).isFalse();
    }

    @Test
    public void isWellFormedToken_nonAscii_isRefused() {
        assertThat(TokenConnection.isWellFormedToken("upstreamtoken-é")).isFalse();
    }

    @Test
    public void isWellFormedToken_emptyOrMissing_isRefused() {
        assertThat(TokenConnection.isWellFormedToken("")).isFalse();
        assertThat(TokenConnection.isWellFormedToken(null)).isFalse();
    }

    @Test
    public void isWellFormedToken_atTheLengthLimit_isAccepted() {
        assertThat(TokenConnection.isWellFormedToken("a".repeat(TokenConnection.MAX_TOKEN_LENGTH))).isTrue();
    }

    @Test
    public void isWellFormedToken_overTheLengthLimit_isRefused() {
        assertThat(TokenConnection.isWellFormedToken("a".repeat(TokenConnection.MAX_TOKEN_LENGTH + 1))).isFalse();
    }
}
