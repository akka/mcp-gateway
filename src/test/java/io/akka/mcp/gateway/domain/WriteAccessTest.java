package io.akka.mcp.gateway.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class WriteAccessTest {

    private static final String WRITER_GROUP = "mcp-gateway-writer";

    private static UserSession sessionIn(String... groups) {
        return new UserSession("user@example.com", "User", null, null, List.of(groups), null, List.of());
    }

    @Test
    public void withNothingEnabled_everyConnectorIsReadOnly() {
        var access = new WriteAccess(Set.of(), WRITER_GROUP);

        assertThat(access.connectorAllows("slack")).isFalse();
        assertThat(access.permits("slack", sessionIn(WRITER_GROUP))).isFalse();
    }

    @Test
    public void onlyEnabledConnectorsAllowWrites() {
        var access = new WriteAccess(Set.of("slack", "google-workspace-gmail"), WRITER_GROUP);

        assertThat(access.connectorAllows("slack")).isTrue();
        assertThat(access.connectorAllows("google-workspace-gmail")).isTrue();
        assertThat(access.connectorAllows("google-workspace-drive")).isFalse();
        assertThat(access.connectorAllows("salesforce")).isFalse();
    }

    @Test
    public void matchIsExact_soAPrefixDoesNotEnableALongerId() {
        var access = new WriteAccess(Set.of("google-workspace-gmail"), WRITER_GROUP);

        assertThat(access.connectorAllows("gmail")).isFalse();
        assertThat(access.connectorAllows("google-workspace")).isFalse();
    }

    @Test
    public void permits_needsBothTheEnabledConnectorAndTheWriterGroup() {
        var access = new WriteAccess(Set.of("slack"), WRITER_GROUP);

        assertThat(access.permits("slack", sessionIn(WRITER_GROUP))).isTrue();
        assertThat(access.permits("slack", sessionIn("mcp-gateway-reader"))).isFalse();
        assertThat(access.permits("salesforce", sessionIn(WRITER_GROUP))).isFalse();
    }

    @Test
    public void blankWriterGroup_grantsNobody() {
        var access = new WriteAccess(Set.of("slack"), "");

        assertThat(access.permits("slack", sessionIn(WRITER_GROUP, ""))).isFalse();
    }

    @Test
    public void resolve_usesTheSavedSelection() {
        var access = WriteAccess.resolve(() -> Set.of("slack"), WRITER_GROUP, e -> {});

        assertThat(access.connectorAllows("slack")).isTrue();
        assertThat(access.writerGroup()).isEqualTo(WRITER_GROUP);
    }

    @Test
    public void resolve_failsClosedAndReportsWhenTheSavedSelectionCannotBeRead() {
        var failures = new ArrayList<RuntimeException>();

        var access = WriteAccess.resolve(() -> { throw new IllegalStateException("store down"); }, WRITER_GROUP, failures::add);

        assertThat(access.connectorAllows("slack")).isFalse();
        assertThat(access.writerGroup()).isEqualTo(WRITER_GROUP);
        assertThat(failures).hasSize(1);
    }
}
