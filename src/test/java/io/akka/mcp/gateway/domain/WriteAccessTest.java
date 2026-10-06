package io.akka.mcp.gateway.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class WriteAccessTest {

    private static final String WRITER_GROUP = "mcp-gateway-writer";

    private static UserSession sessionIn(String... groups) {
        return new UserSession("user@example.com", "User", null, null, List.of(groups), null, List.of());
    }

    @Test
    public void unconfigured_leavesEveryConnectorReadOnly() {
        assertThat(WriteAccess.parse("", WRITER_GROUP).connectorAllows("slack")).isFalse();
        assertThat(WriteAccess.parse(null, WRITER_GROUP).connectorAllows("slack")).isFalse();
        assertThat(WriteAccess.parse("  , ,", WRITER_GROUP).connectorAllows("slack")).isFalse();
    }

    @Test
    public void listedConnectors_areEnabled_andTheRestAreNot() {
        var access = WriteAccess.parse("slack, google-workspace-gmail ,google-workspace-docs", WRITER_GROUP);

        assertThat(access.connectorAllows("slack")).isTrue();
        assertThat(access.connectorAllows("google-workspace-gmail")).isTrue();
        assertThat(access.connectorAllows("google-workspace-docs")).isTrue();
        assertThat(access.connectorAllows("google-workspace-drive")).isFalse();
        assertThat(access.connectorAllows("salesforce")).isFalse();
    }

    @Test
    public void matchIsExact_soAPrefixDoesNotEnableALongerId() {
        var access = WriteAccess.parse("google-workspace-gmail", WRITER_GROUP);

        assertThat(access.connectorAllows("gmail")).isFalse();
        assertThat(access.connectorAllows("google-workspace")).isFalse();
    }

    @Test
    public void permits_needsBothTheEnabledConnectorAndTheWriterGroup() {
        var access = WriteAccess.parse("slack", WRITER_GROUP);

        assertThat(access.permits("slack", sessionIn(WRITER_GROUP))).isTrue();
        assertThat(access.permits("slack", sessionIn("mcp-gateway-reader"))).isFalse();
        assertThat(access.permits("salesforce", sessionIn(WRITER_GROUP))).isFalse();
    }

    @Test
    public void blankWriterGroup_grantsNobody() {
        var access = WriteAccess.parse("slack", "");

        assertThat(access.permits("slack", sessionIn(WRITER_GROUP, ""))).isFalse();
    }
}
