package io.akka.mcp.gateway.application;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local guidance is exempt from the write gate, so no downstream connector may ever claim to be it:
 * that would let its write tools skip the gate.
 */
public class McpClientsTest {

    @Test
    public void noDownstreamConnector_isLocalGuidance() {
        var connectors = McpClients.serviceClients(null, null, ConfigFactory.load());

        assertThat(connectors).isNotEmpty();
        assertThat(connectors).noneMatch(RemoteMcpClient::isLocalGuidance);
    }

    @Test
    public void theHowToClient_isLocalGuidance() {
        var howTo = new HowToMcpClient("http://localhost:9000", "", "", List.of());

        assertThat(howTo.isLocalGuidance()).isTrue();
    }
}
