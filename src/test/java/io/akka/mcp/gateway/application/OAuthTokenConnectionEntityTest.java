package io.akka.mcp.gateway.application;

import akka.javasdk.testkit.KeyValueEntityTestKit;
import io.akka.mcp.gateway.application.OAuthTokenConnectionEntity.Outcome;
import io.akka.mcp.gateway.domain.OAuthTokenConnection;
import io.akka.mcp.gateway.domain.OAuthTokenConnection.Pending;
import io.akka.mcp.gateway.domain.OAuthTokenConnection.Tokens;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Renewing calls a token endpoint, so that path is covered by the integration test; the rest is here. */
public class OAuthTokenConnectionEntityTest {

    private static final String RESOURCE = "https://mcp.example.com/mcp";
    private static final Instant LATER = Instant.now().plusSeconds(3600);

    private static KeyValueEntityTestKit<OAuthTokenConnection, OAuthTokenConnectionEntity> testKit() {
        return KeyValueEntityTestKit.of(TokenConnectionEntity.keyFor("reports", "user@example.com"), OAuthTokenConnectionEntity::new);
    }

    private static Tokens tokens(String access, Optional<String> refresh, Instant expiresAt) {
        return Tokens.issued(access, refresh, Instant.now().minusSeconds(3600), expiresAt, "client-1", "https://auth.example.com/token", RESOURCE);
    }

    private static KeyValueEntityTestKit<OAuthTokenConnection, OAuthTokenConnectionEntity> connectedWith(Tokens tokens) {
        var kit = testKit();
        kit.method(OAuthTokenConnectionEntity::connect).invoke(tokens);
        return kit;
    }

    private static Pending pendingFor(String state, Instant expiresAt) {
        return new Pending(state, "verifier", "client-1", "https://auth.example.com/token", RESOURCE, expiresAt);
    }

    private static boolean connected(KeyValueEntityTestKit<OAuthTokenConnection, OAuthTokenConnectionEntity> kit) {
        return kit.method(OAuthTokenConnectionEntity::getStatus).invoke(RESOURCE).getReply().connected();
    }

    private static OAuthTokenConnectionEntity.Bearer bearer(KeyValueEntityTestKit<OAuthTokenConnection, OAuthTokenConnectionEntity> kit) {
        return kit.method(OAuthTokenConnectionEntity::getAccessToken).invoke(RESOURCE).getReply();
    }

    @Test
    public void whenNothingIsKept_theUserIsNotConnected() {
        var kit = testKit();

        assertThat(connected(kit)).isFalse();
        assertThat(bearer(kit).outcome()).isEqualTo(Outcome.NOT_CONNECTED);
    }

    @Test
    public void aCurrentAccessToken_isHandedOver() {
        var kit = connectedWith(tokens("access-1", Optional.of("refresh-1"), LATER));

        assertThat(connected(kit)).isTrue();
        assertThat(bearer(kit)).satisfies(bearer -> {
            assertThat(bearer.outcome()).isEqualTo(Outcome.CURRENT);
            assertThat(bearer.accessToken()).contains("access-1");
        });
    }

    @Test
    public void tokensIssuedForAnotherResource_areNotHandedOver_andNotConnected() {
        var kit = connectedWith(tokens("access-1", Optional.of("refresh-1"), LATER));

        assertThat(kit.method(OAuthTokenConnectionEntity::getAccessToken).invoke("https://elsewhere.example.com/mcp").getReply().outcome())
                .isEqualTo(Outcome.NOT_CONNECTED);
        assertThat(kit.method(OAuthTokenConnectionEntity::getStatus).invoke("https://elsewhere.example.com/mcp").getReply().connected()).isFalse();
    }

    @Test
    public void anExpiredAccessToken_withARefreshToken_stillCountsAsConnected() {
        assertThat(connected(connectedWith(tokens("access-1", Optional.of("refresh-1"), Instant.now().minusSeconds(5))))).isTrue();
    }

    @Test
    public void anExpiredAccessToken_withoutARefreshToken_endsTheSignIn_butKeepsAStartedOne() {
        var kit = connectedWith(tokens("access-1", Optional.empty(), Instant.now().minusSeconds(5)));
        kit.method(OAuthTokenConnectionEntity::begin).invoke(pendingFor("state-1", LATER));

        assertThat(connected(kit)).isFalse();
        assertThat(bearer(kit).outcome()).isEqualTo(Outcome.ENDED_WITHOUT_REFRESH_TOKEN);
        assertThat(kit.getState().tokens()).isEmpty();
        assertThat(kit.getState().pendingFor("state-1")).isPresent();
    }

    @Test
    public void rejectAccessToken_ofAOldToken_withARefreshToken_marksItExpiredAndKeepsTheUserConnected() {
        var kit = connectedWith(tokens("access-1", Optional.of("refresh-1"), LATER));

        var disconnected = kit.method(OAuthTokenConnectionEntity::rejectAccessToken).invoke("access-1").getReply();

        assertThat(disconnected).isFalse();
        assertThat(connected(kit)).isTrue();
        assertThat(kit.getState().tokens()).hasValueSatisfying(held -> assertThat(held.isExpired(Instant.now())).isTrue());
    }

    @Test
    public void rejectAccessToken_forATokenNoLongerHeld_orWhenNothingIsHeld_changesNothing_andDisconnectsNoOne() {
        var kit = connectedWith(tokens("access-2", Optional.of("refresh-2"), LATER));

        assertThat(kit.method(OAuthTokenConnectionEntity::rejectAccessToken).invoke("access-1").getReply()).isFalse();
        assertThat(kit.getState().tokens()).hasValueSatisfying(held -> assertThat(held.isExpired(Instant.now())).isFalse());
        assertThat(testKit().method(OAuthTokenConnectionEntity::rejectAccessToken).invoke("access-1").getReply()).isFalse();
    }

    @Test
    public void aTokenThatHasBeenExpiredForTenMinutes_endsTheSignIn_withoutTryingToRenewIt() {
        var kit = connectedWith(tokens("access-1", Optional.of("refresh-1"), Instant.now().minusSeconds(660)));

        assertThat(bearer(kit).outcome()).isEqualTo(Outcome.RENEWAL_KEEPS_FAILING);
        assertThat(kit.getState().tokens()).isEmpty();
    }

    @Test
    public void afterAFailedRenewal_aTokenThatIsStillGood_isHandedOver_andOneThatLapsed_isNot() {
        var stillGood = connectedWith(tokens("access-1", Optional.of("refresh-1"), Instant.now().plusSeconds(30)));
        var lapsed = connectedWith(tokens("access-2", Optional.of("refresh-2"), Instant.now().minusSeconds(5)));
        var waiting = Instant.now().plusSeconds(15);
        stillGood.method(OAuthTokenConnectionEntity::connect).invoke(tokens("access-1", Optional.of("refresh-1"), Instant.now().plusSeconds(30)).retryingNotBefore(waiting));
        lapsed.method(OAuthTokenConnectionEntity::connect).invoke(tokens("access-2", Optional.of("refresh-2"), Instant.now().minusSeconds(5)).retryingNotBefore(waiting));

        assertThat(bearer(stillGood).outcome()).isEqualTo(Outcome.CURRENT);
        assertThat(bearer(lapsed).outcome()).isEqualTo(Outcome.TRY_LATER);
    }

    @Test
    public void takePending_handsOverTheStartedSignInOnce() {
        var kit = testKit();
        kit.method(OAuthTokenConnectionEntity::begin).invoke(pendingFor("state-1", LATER));

        assertThat(kit.method(OAuthTokenConnectionEntity::takePending).invoke("state-1").getReply()).isPresent();
        assertThat(kit.method(OAuthTokenConnectionEntity::takePending).invoke("state-1").getReply()).isEmpty();
    }

    @Test
    public void takePending_withAnotherState_handsOverNothing_andKeepsTheStartedSignIn() {
        var kit = testKit();
        kit.method(OAuthTokenConnectionEntity::begin).invoke(pendingFor("state-1", LATER));

        assertThat(kit.method(OAuthTokenConnectionEntity::takePending).invoke("state-2").getReply()).isEmpty();

        assertThat(kit.method(OAuthTokenConnectionEntity::takePending).invoke("state-1").getReply()).isPresent();
    }

    @Test
    public void takePending_afterTheSignInExpired_handsOverNothing() {
        var kit = testKit();
        kit.method(OAuthTokenConnectionEntity::begin).invoke(pendingFor("state-1", Instant.now().minusSeconds(1)));

        assertThat(kit.method(OAuthTokenConnectionEntity::takePending).invoke("state-1").getReply()).isEmpty();
    }

    @Test
    public void disconnect_forgetsTheTokensAndAnyStartedSignIn() {
        var kit = connectedWith(tokens("access-1", Optional.of("refresh-1"), LATER));
        kit.method(OAuthTokenConnectionEntity::begin).invoke(pendingFor("state-1", LATER));

        kit.method(OAuthTokenConnectionEntity::disconnect).invoke();

        assertThat(connected(kit)).isFalse();
        assertThat(kit.getState().pending()).isEmpty();
    }
}
