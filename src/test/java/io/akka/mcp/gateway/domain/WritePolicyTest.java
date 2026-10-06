package io.akka.mcp.gateway.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class WritePolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    @Test
    public void startsWithNothingEnabledAtVersionZero() {
        var policy = WritePolicy.nothingEnabled();

        assertThat(policy.enabledMcpIds()).isEmpty();
        assertThat(policy.version()).isZero();
        assertThat(policy.updatedBy()).isNull();
    }

    @Test
    public void select_tidiesTheIdsAndRecordsWhoAndWhen() {
        var policy = WritePolicy.nothingEnabled()
                .select(List.of("slack", " google-workspace-gmail ", "slack", ""), "admin@example.com", NOW);

        assertThat(policy.enabledMcpIds()).containsExactly("google-workspace-gmail", "slack");
        assertThat(policy.updatedBy()).isEqualTo("admin@example.com");
        assertThat(policy.updatedAt()).isEqualTo(NOW);
    }

    @Test
    public void select_countsEverySavedChange() {
        var first = WritePolicy.nothingEnabled().select(List.of("slack"), "a@example.com", NOW);
        var second = first.select(List.of(), "b@example.com", NOW);

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.enabledMcpIds()).isEmpty();
    }

    @Test
    public void isBasedOn_matchesOnlyTheCurrentVersion() {
        var policy = WritePolicy.nothingEnabled().select(List.of("slack"), "a@example.com", NOW);

        assertThat(policy.isBasedOn(1)).isTrue();
        assertThat(policy.isBasedOn(0)).isFalse();
    }

    @Test
    public void sameSelectionAs_ignoresOrderDuplicatesAndBlanks() {
        var policy = WritePolicy.nothingEnabled().select(List.of("slack", "hubspot"), "a@example.com", NOW);

        assertThat(policy.sameSelectionAs(List.of("hubspot", "slack", "slack", " "))).isTrue();
        assertThat(policy.sameSelectionAs(List.of("slack"))).isFalse();
    }

    @Test
    public void addedAndRemovedSince_describeTheDifferenceFromThePreviousSelection() {
        var policy = WritePolicy.nothingEnabled().select(List.of("slack", "hubspot"), "a@example.com", NOW);
        var previous = List.of("hubspot", "zoho-desk");

        assertThat(policy.addedSince(previous)).containsExactly("slack");
        assertThat(policy.removedSince(previous)).containsExactly("zoho-desk");
    }
}
