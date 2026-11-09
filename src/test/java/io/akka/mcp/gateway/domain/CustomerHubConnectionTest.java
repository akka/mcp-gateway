package io.akka.mcp.gateway.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CustomerHubConnectionTest {

    @Test
    public void emptyConnection_isNotConnected() {
        assertThat(CustomerHubConnection.empty().isConnected()).isFalse();
    }

    @Test
    public void withToken_isConnected_andDisconnectedForgetsIt() {
        var connected = CustomerHubConnection.empty().withToken("hubtoken-abc");

        assertThat(connected.isConnected()).isTrue();
        assertThat(connected.token()).contains("hubtoken-abc");
        assertThat(connected.disconnected().isConnected()).isFalse();
        assertThat(connected.disconnected().token()).isEmpty();
    }

    @Test
    public void toString_neverShowsTheToken() {
        var connected = CustomerHubConnection.empty().withToken("hubtoken-super-secret");

        assertThat(connected.toString()).doesNotContain("super-secret");
    }

    @Test
    public void isWellFormedToken_printableAscii_isAccepted() {
        assertThat(CustomerHubConnection.isWellFormedToken("hubtoken-Abc-123._~+/=")).isTrue();
    }

    @Test
    public void isWellFormedToken_whitespaceOrControlCharacters_isRefused() {
        assertThat(CustomerHubConnection.isWellFormedToken("hubtoken abc")).isFalse();
        assertThat(CustomerHubConnection.isWellFormedToken("hubtoken\tabc")).isFalse();
        assertThat(CustomerHubConnection.isWellFormedToken("hubtoken\r\nX-Injected: 1")).isFalse();
        assertThat(CustomerHubConnection.isWellFormedToken("hubtoken\u0000abc")).isFalse();
    }

    @Test
    public void isWellFormedToken_nonAscii_isRefused() {
        assertThat(CustomerHubConnection.isWellFormedToken("hubtoken-é")).isFalse();
    }

    @Test
    public void isWellFormedToken_emptyOrMissing_isRefused() {
        assertThat(CustomerHubConnection.isWellFormedToken("")).isFalse();
        assertThat(CustomerHubConnection.isWellFormedToken(null)).isFalse();
    }

    @Test
    public void isWellFormedToken_atTheLengthLimit_isAccepted() {
        assertThat(CustomerHubConnection.isWellFormedToken("a".repeat(CustomerHubConnection.MAX_TOKEN_LENGTH))).isTrue();
    }

    @Test
    public void isWellFormedToken_overTheLengthLimit_isRefused() {
        assertThat(CustomerHubConnection.isWellFormedToken("a".repeat(CustomerHubConnection.MAX_TOKEN_LENGTH + 1))).isFalse();
    }
}
