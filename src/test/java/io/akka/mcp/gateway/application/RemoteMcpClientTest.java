package io.akka.mcp.gateway.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class RemoteMcpClientTest {

    private static RemoteMcpClient clientConnected(boolean connected) {
        return new RemoteMcpClient() {
            public String getMcpId() { return "stub"; }
            public String getMcpName() { return "Stub"; }
            public String getRequiredOktaAppId() { return ""; }
            public boolean isConnected(String userId) { return connected; }
            public boolean canHandle(String toolName) { return true; }
            public List<ToolEntry> listTools(String userId) { return List.of(); }
            public ToolCallResult callTool(String userId, String toolName, Map<String, Object> arguments) { return null; }
            public HowToContent howTo(String dashboardUrl) { return null; }
        };
    }

    @Test
    public void connection_ofAClientThatOnlyKnowsIsConnected_isConnectedOrNotConnected() {
        assertThat(clientConnected(true).connection("user")).isEqualTo(RemoteMcpClient.Connection.CONNECTED);
        assertThat(clientConnected(false).connection("user")).isEqualTo(RemoteMcpClient.Connection.NOT_CONNECTED);
    }
}
