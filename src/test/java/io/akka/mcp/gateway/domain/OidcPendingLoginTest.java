package io.akka.mcp.gateway.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class OidcPendingLoginTest {

    @Test
    public void landingPath_isTheStoredReturnPath() {
        var pending = new OidcPendingLogin("", Instant.now().plusSeconds(600), "verifier", "/interactions");

        assertThat(pending.landingPath()).isEqualTo("/interactions");
    }

    @Test
    public void landingPath_withoutStoredReturnPath_isTheDashboard() {
        var pending = new OidcPendingLogin("", Instant.now().plusSeconds(600), "verifier", null);

        assertThat(pending.landingPath()).isEqualTo(OidcPendingLogin.DASHBOARD);
    }
}
