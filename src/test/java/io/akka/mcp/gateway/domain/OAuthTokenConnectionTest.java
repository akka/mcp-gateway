package io.akka.mcp.gateway.domain;

import io.akka.mcp.gateway.domain.OAuthTokenConnection.Pending;
import io.akka.mcp.gateway.domain.OAuthTokenConnection.Tokens;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class OAuthTokenConnectionTest {

    private static final String RESOURCE = "https://mcp.example.com/mcp";
    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

    private static Tokens tokensIssuedAt(Instant issuedAt, Duration lifetime, Optional<String> refreshToken) {
        return Tokens.issued("access-1", refreshToken, issuedAt, issuedAt.plus(lifetime), "client-1", "https://auth.example.com/token", RESOURCE);
    }

    private static OAuthTokenConnection connectedWith(Tokens tokens) {
        return OAuthTokenConnection.empty().withTokens(tokens);
    }

    private static Pending pending(String state, Instant expiresAt) {
        return new Pending(state, "verifier", "client-1", "https://auth.example.com/token", RESOURCE, expiresAt);
    }

    @Test
    public void anAccessToken_isRenewedAMinuteBeforeItExpires() {
        var tokens = tokensIssuedAt(NOW, Duration.ofSeconds(120), Optional.of("refresh-1"));

        assertThat(tokens.isExpired(NOW.plusSeconds(50))).isFalse();
        assertThat(tokens.isExpired(NOW.plusSeconds(70))).isTrue();
    }

    @Test
    public void aLongLivedAccessToken_isRenewedAMinuteBeforeItExpires_notHalfItsLifetime() {
        var tokens = tokensIssuedAt(NOW, Duration.ofHours(1), Optional.of("refresh-1"));

        assertThat(tokens.isExpired(NOW.plus(Duration.ofMinutes(50)))).isFalse();
        assertThat(tokens.isExpired(NOW.plus(Duration.ofMinutes(59).plusSeconds(30)))).isTrue();
    }

    @Test
    public void aShortLivedAccessToken_isRenewedHalfItsLifetimeBeforeItExpires() {
        var tokens = tokensIssuedAt(NOW, Duration.ofSeconds(10), Optional.of("refresh-1"));

        assertThat(tokens.isExpired(NOW.plusSeconds(3))).isFalse();
        assertThat(tokens.isExpired(NOW.plusSeconds(6))).isTrue();
    }

    @Test
    public void aTokenMarkedExpired_isExpired() {
        assertThat(tokensIssuedAt(NOW, Duration.ofHours(1), Optional.of("refresh-1")).markedExpiredAt(NOW).isExpired(NOW)).isTrue();
    }

    @Test
    public void lapsedTokens_areUsableOnlyWhileTheyCanBeRenewed() {
        var lapsed = NOW.plusSeconds(7200);

        assertThat(tokensIssuedAt(NOW, Duration.ofHours(1), Optional.of("refresh-1")).isUsable(lapsed)).isTrue();
        assertThat(tokensIssuedAt(NOW, Duration.ofHours(1), Optional.empty()).isUsable(lapsed)).isFalse();
    }

    @Test
    public void aTokenWithoutARefreshToken_isUsableUntilItReallyExpires_notUntilTheRenewalMargin() {
        var tokens = tokensIssuedAt(NOW, Duration.ofSeconds(120), Optional.empty());

        assertThat(tokens.isUsable(NOW.plusSeconds(110))).isTrue();
        assertThat(tokens.isUsable(NOW.plusSeconds(120))).isFalse();
    }

    @Test
    public void aTokenDueForRenewal_isStillGoodUntilItLapses() {
        var tokens = tokensIssuedAt(NOW, Duration.ofSeconds(120), Optional.of("refresh-1"));

        assertThat(tokens.isExpired(NOW.plusSeconds(90))).isTrue();
        assertThat(tokens.hasLapsed(NOW.plusSeconds(90))).isFalse();
        assertThat(tokens.hasLapsed(NOW.plusSeconds(120))).isTrue();
    }

    @Test
    public void afterAFailedRenewal_theNextOneWaits_andARenewalClearsTheWait() {
        var waiting = tokensIssuedAt(NOW, Duration.ofHours(1), Optional.of("refresh-1")).retryingNotBefore(NOW.plusSeconds(15));

        assertThat(waiting.isWaitingToRetry(NOW.plusSeconds(14))).isTrue();
        assertThat(waiting.isWaitingToRetry(NOW.plusSeconds(15))).isFalse();
        assertThat(waiting.renewedWith("access-2", Optional.of("refresh-2"), NOW, NOW.plusSeconds(3600)).isWaitingToRetry(NOW)).isFalse();
    }

    @Test
    public void aConnection_isOnlyForTheResourceItsTokensWereIssuedFor() {
        var connection = connectedWith(tokensIssuedAt(NOW, Duration.ofHours(1), Optional.empty()));

        assertThat(connection.isConnectedFor(RESOURCE, NOW)).isTrue();
        assertThat(connection.isConnectedFor("https://elsewhere.example.com/mcp", NOW)).isFalse();
    }

    @Test
    public void aRefusedToken_thatWasNotJustIssued_andCanBeRenewed_isOnlyMarkedExpired() {
        var connection = connectedWith(tokensIssuedAt(NOW, Duration.ofHours(1), Optional.of("refresh-1")));

        var after = connection.afterRefusing("access-1", NOW.plusSeconds(31));

        assertThat(after.tokens()).hasValueSatisfying(tokens -> assertThat(tokens.isExpired(NOW.plusSeconds(31))).isTrue());
    }

    @Test
    public void aRefusedToken_thatWasJustIssued_isForgotten() {
        var connection = connectedWith(tokensIssuedAt(NOW, Duration.ofHours(1), Optional.of("refresh-1")));

        assertThat(connection.afterRefusing("access-1", NOW.plusSeconds(5)).tokens()).isEmpty();
    }

    @Test
    public void aRefusedToken_thatCannotBeRenewed_isForgotten() {
        var connection = connectedWith(tokensIssuedAt(NOW, Duration.ofHours(1), Optional.empty()));

        assertThat(connection.afterRefusing("access-1", NOW.plusSeconds(600)).tokens()).isEmpty();
    }

    @Test
    public void aRefusalOfATokenNoLongerHeld_changesNothing() {
        var connection = connectedWith(tokensIssuedAt(NOW, Duration.ofHours(1), Optional.of("refresh-1")));

        assertThat(connection.afterRefusing("access-0", NOW.plusSeconds(600))).isEqualTo(connection);
    }

    @Test
    public void forgettingTheTokens_keepsASignInTheUserJustStarted() {
        var connection = connectedWith(tokensIssuedAt(NOW, Duration.ofHours(1), Optional.empty())).withPending(pending("state-1", NOW.plusSeconds(600)), NOW);

        assertThat(connection.withoutTokens().pendingFor("state-1")).isPresent();
        assertThat(connection.afterRefusing("access-1", NOW).pendingFor("state-1")).isPresent();
    }

    @Test
    public void onlyTheLastFewStartedSignIns_areKept() {
        var connection = OAuthTokenConnection.empty();
        for (var i = 1; i <= 5; i++) connection = connection.withPending(pending("state-" + i, NOW.plusSeconds(600)), NOW);

        assertThat(connection.pending()).extracting(Pending::state).containsExactly("state-3", "state-4", "state-5");
    }

    @Test
    public void startingASignIn_dropsOnesThatHaveExpired() {
        var connection = OAuthTokenConnection.empty().withPending(pending("old", NOW.minusSeconds(1)), NOW.minusSeconds(700));

        assertThat(connection.withPending(pending("new", NOW.plusSeconds(600)), NOW).pending()).extracting(Pending::state).containsExactly("new");
    }

    @Test
    public void aStartedSignIn_isValidForTenMinutes() {
        var started = Pending.startedAt(NOW, "state-1", "verifier", "client-1", "https://auth.example.com/token", RESOURCE);

        assertThat(started.isValidAt(NOW.plusSeconds(599))).isTrue();
        assertThat(started.isValidAt(NOW.plusSeconds(600))).isFalse();
    }

    @Test
    public void aRegistration_isReusedForAnHour_forTheSameRedirectAddressAndResource() {
        var registration = new OAuthClientRegistration("client-1", "https://auth.example.com/authorize", "https://auth.example.com/token", "mcp:read",
                "https://gateway.example.com/callback", RESOURCE, NOW);

        assertThat(registration.isReusableFor("https://gateway.example.com/callback", RESOURCE, NOW.plusSeconds(3599))).isTrue();
        assertThat(registration.isReusableFor("https://gateway.example.com/callback", RESOURCE, NOW.plusSeconds(3600))).isFalse();
        assertThat(registration.isReusableFor("https://other.example.com/callback", RESOURCE, NOW)).isFalse();
        assertThat(registration.isReusableFor("https://gateway.example.com/callback", "https://elsewhere.example.com/mcp", NOW)).isFalse();
    }
}
