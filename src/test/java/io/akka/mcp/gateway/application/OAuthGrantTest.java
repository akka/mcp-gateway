package io.akka.mcp.gateway.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class OAuthGrantTest {

    private static Duration lifetimeOf(OAuthGrant.Issued issued) {
        return Duration.between(issued.issuedAt(), issued.expiresAt());
    }

    @Test
    public void anAnswerWithAnAccessToken_givesTheTokensAndTheirLifetime() {
        var issued = OAuthGrant.issuedFrom("{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":600}", Optional.empty()).orElseThrow();

        assertThat(issued.accessToken()).isEqualTo("a");
        assertThat(issued.refreshToken()).contains("r");
        assertThat(lifetimeOf(issued)).isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    public void aRefreshAnswerWithoutANewRefreshToken_keepsTheOldOne() {
        var issued = OAuthGrant.issuedFrom("{\"access_token\":\"a\",\"expires_in\":600}", Optional.of("old")).orElseThrow();

        assertThat(issued.refreshToken()).contains("old");
    }

    @Test
    public void aMissingOrUnusableLifetime_countsAsAnHour() {
        assertThat(lifetimeOf(OAuthGrant.issuedFrom("{\"access_token\":\"a\"}", Optional.empty()).orElseThrow())).isEqualTo(Duration.ofHours(1));
        assertThat(lifetimeOf(OAuthGrant.issuedFrom("{\"access_token\":\"a\",\"expires_in\":0}", Optional.empty()).orElseThrow()))
                .isEqualTo(Duration.ofHours(1));
        assertThat(lifetimeOf(OAuthGrant.issuedFrom("{\"access_token\":\"a\",\"expires_in\":\"soon\"}", Optional.empty()).orElseThrow()))
                .isEqualTo(Duration.ofHours(1));
    }

    @Test
    public void aLongLifetime_isCappedAtThirtyDays() {
        var issued = OAuthGrant.issuedFrom("{\"access_token\":\"a\",\"expires_in\":7776000}", Optional.empty()).orElseThrow();

        assertThat(lifetimeOf(issued)).isEqualTo(Duration.ofDays(30));
        assertThat(issued.issuedAt()).isBefore(Instant.now().plusSeconds(1));
    }

    @Test
    public void anAnswerWithoutAnAccessToken_orNotJson_givesNothing() {
        assertThat(OAuthGrant.issuedFrom("{\"expires_in\":600}", Optional.empty())).isEmpty();
        assertThat(OAuthGrant.issuedFrom("<html>", Optional.empty())).isEmpty();
    }

    @Test
    public void theErrorCode_isTakenOnlyFromA400Or401Answer() {
        assertThat(OAuthGrant.errorCode(400, "{\"error\":\"invalid_grant\"}")).contains("invalid_grant");
        assertThat(OAuthGrant.errorCode(401, "{\"error\":\"invalid_client\"}")).contains("invalid_client");
        assertThat(OAuthGrant.errorCode(503, "{\"error\":\"invalid_grant\"}")).isEmpty();
        assertThat(OAuthGrant.errorCode(400, "not json")).isEmpty();
        assertThat(OAuthGrant.errorCode(400, "{}")).isEmpty();
    }
}
