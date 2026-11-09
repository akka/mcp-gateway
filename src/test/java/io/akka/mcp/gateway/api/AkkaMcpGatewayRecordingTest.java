package io.akka.mcp.gateway.api;

import io.akka.mcp.gateway.application.RemoteMcpClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the gateway keeps of a call depends on whether its client may have its content stored. */
public class AkkaMcpGatewayRecordingTest {

    private static final Exception UPSTREAM_FAILURE = new RuntimeException("UPSTREAM SAID THIS");

    private static RemoteMcpClient client(boolean recordsContent) {
        return new RemoteMcpClient() {
            public String getMcpId() { return "stub"; }
            public String getMcpName() { return "Stub"; }
            public String getRequiredOktaAppId() { return ""; }
            public boolean isConnected(String userId) { return true; }
            public boolean canHandle(String toolName) { return true; }
            public List<ToolEntry> listTools(String userId) { return List.of(); }
            public ToolCallResult callTool(String userId, String toolName, Map<String, Object> arguments) { return null; }
            public HowToContent howTo(String dashboardUrl) { return null; }
            public boolean recordsToolContent() { return recordsContent; }
        };
    }

    @Test
    public void recordedArguments_whenContentIsKept_holdsThem() {
        assertThat(AkkaMcpGateway.recordedArguments(client(true), Map.of("id", "42"))).containsEntry("arguments", "{\"id\":\"42\"}");
    }

    @Test
    public void recordedArguments_whenContentIsNotKept_holdsOnlyTheMarker() {
        assertThat(AkkaMcpGateway.recordedArguments(client(false), Map.of("id", "42")))
                .containsOnlyKeys("arguments").containsValue(AkkaMcpGateway.NOT_KEPT);
    }

    @Test
    public void recordedOutput_whenContentIsKept_isTheTextCutAtTheLimit() {
        assertThat(AkkaMcpGateway.recordedOutput(client(true), "short")).isEqualTo("short");
        assertThat(AkkaMcpGateway.recordedOutput(client(true), "x".repeat(AkkaMcpGateway.MAX_RECORDED_OUTPUT + 1)))
                .isEqualTo("x".repeat(AkkaMcpGateway.MAX_RECORDED_OUTPUT) + AkkaMcpGateway.TRUNCATED_MARKER);
        assertThat(AkkaMcpGateway.recordedOutput(client(true), null)).isNull();
    }

    @Test
    public void recordedOutput_whenContentIsNotKept_isTheMarker() {
        assertThat(AkkaMcpGateway.recordedOutput(client(false), "secret result")).isEqualTo(AkkaMcpGateway.NOT_KEPT);
    }

    @Test
    public void recordedError_whenContentIsKept_isTheMessage_andWhenNot_onlyTheKindOfFailure() {
        assertThat(AkkaMcpGateway.recordedError(client(true), UPSTREAM_FAILURE)).isEqualTo("UPSTREAM SAID THIS");
        assertThat(AkkaMcpGateway.recordedError(client(false), UPSTREAM_FAILURE)).isEqualTo("RuntimeException");
    }

    @Test
    public void failureText_whenContentIsKept_isTheMessage_andWhenNot_aGenericLine() {
        assertThat(AkkaMcpGateway.failureText(client(true), UPSTREAM_FAILURE)).isEqualTo("UPSTREAM SAID THIS");
        assertThat(AkkaMcpGateway.failureText(client(false), UPSTREAM_FAILURE)).isEqualTo(AkkaMcpGateway.GENERIC_FAILURE_TEXT);
    }

    @Test
    public void loggable_whenContentIsNotKept_neverHoldsWhatTheUpstreamSaid() {
        assertThat(AkkaMcpGateway.loggable(client(true), UPSTREAM_FAILURE)).isEqualTo("UPSTREAM SAID THIS");
        assertThat(AkkaMcpGateway.loggable(client(false), UPSTREAM_FAILURE)).isEqualTo("RuntimeException");
    }
}
