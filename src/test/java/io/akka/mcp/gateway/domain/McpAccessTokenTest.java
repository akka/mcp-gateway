package io.akka.mcp.gateway.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class McpAccessTokenTest {

    @Test
    public void asUserSession_preservesGroupsAndApps() {
        var token = new McpAccessToken(
                "user@lightbend.com", "User",
                List.of("mcp-gateway-reader", "mcp-gateway-writer"),
                List.of(new UserSession.App("0oa123", "Salesforce"), new UserSession.App("0oa456", "Zoho Desk")),
                "client-1", Instant.now().plusSeconds(3600));

        var session = token.asUserSession();

        assertThat(session.email()).isEqualTo("user@lightbend.com");
        assertThat(session.displayName()).isEqualTo("User");
        assertThat(session.groups()).containsExactly("mcp-gateway-reader", "mcp-gateway-writer");
        assertThat(session.apps()).containsExactly(
                new UserSession.App("0oa123", "Salesforce"),
                new UserSession.App("0oa456", "Zoho Desk"));
        assertThat(session.hasRole("mcp-gateway-writer")).isTrue();
        assertThat(session.hasApp("0oa123")).isTrue();
        assertThat(session.hasApp("0oa999")).isFalse();
    }

    @Test
    public void asUserSession_withNoGroupsOrApps_projectsEmptyLists() {
        var token = new McpAccessToken(
                "user@lightbend.com", "User", List.of(), List.of(), "client-1", Instant.now().plusSeconds(3600));

        var session = token.asUserSession();

        assertThat(session.groups()).isEmpty();
        assertThat(session.apps()).isEmpty();
    }
}
