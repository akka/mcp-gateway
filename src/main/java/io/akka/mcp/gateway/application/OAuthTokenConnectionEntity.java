package io.akka.mcp.gateway.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.mcp.gateway.domain.OAuthTokenConnection;
import io.akka.mcp.gateway.domain.OAuthTokenConnection.Pending;
import io.akka.mcp.gateway.domain.OAuthTokenConnection.Tokens;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Keyed like {@link TokenConnectionEntity}. It renews an expired access token itself: it handles one command at a time,
 * so a refresh token is spent once, and the renewed tokens are saved in the same step.
 */
@Component(id = "oauth-token-connection")
public class OAuthTokenConnectionEntity extends KeyValueEntity<OAuthTokenConnection> {

    private static final Duration RETRY_AFTER_FAILED_RENEWAL = Duration.ofSeconds(15);
    private static final Duration GIVE_UP_AFTER_LAPSED_FOR = Duration.ofMinutes(10);

    public enum Outcome { CURRENT, NOT_CONNECTED, ENDED_WITHOUT_REFRESH_TOKEN, GRANT_GONE, CLIENT_UNKNOWN, RENEWAL_KEEPS_FAILING, TRY_LATER }

    public record Bearer(Outcome outcome, Optional<String> accessToken) {
        static Bearer of(Outcome outcome) {
            return new Bearer(outcome, Optional.empty());
        }

        @Override
        public String toString() {
            return "Bearer[" + outcome + "]";
        }
    }

    @Override
    public OAuthTokenConnection emptyState() {
        return OAuthTokenConnection.empty();
    }

    public ReadOnlyEffect<TokenConnectionEntity.Status> getStatus(String resource) {
        return effects().reply(new TokenConnectionEntity.Status(currentState().isConnectedFor(resource, Instant.now())));
    }

    /** The access token to forward for this resource, renewed first if it is due; a token that is still good is handed over when renewing fails. */
    public Effect<Bearer> getAccessToken(String resource) {
        var held = currentState().tokens().filter(tokens -> tokens.isFor(resource));
        if (held.isEmpty()) return effects().reply(Bearer.of(Outcome.NOT_CONNECTED));
        var tokens = held.get();
        var now = Instant.now();
        if (!tokens.canRefresh() && tokens.hasLapsed(now)) return forget(Outcome.ENDED_WITHOUT_REFRESH_TOKEN);
        if (!tokens.canRefresh() || !tokens.isExpired(now)) return effects().reply(current(tokens));
        if (tokens.hasLapsedFor(now, GIVE_UP_AFTER_LAPSED_FOR)) return forget(Outcome.RENEWAL_KEEPS_FAILING);
        if (tokens.isWaitingToRetry(now)) return effects().reply(whileRenewalFails(tokens, now));
        return switch (OAuthGrant.refresh(tokens.tokenEndpoint(), tokens.clientId(), tokens.refreshToken().orElseThrow(), tokens.resource())) {
            case OAuthGrant.Refreshed refreshed -> {
                var issued = refreshed.issued();
                var renewed = tokens.renewedWith(issued.accessToken(), issued.refreshToken(), issued.issuedAt(), issued.expiresAt());
                yield effects().updateState(currentState().withTokens(renewed)).thenReply(current(renewed));
            }
            case OAuthGrant.GrantGone gone -> forget(gone.clientUnknown() ? Outcome.CLIENT_UNKNOWN : Outcome.GRANT_GONE);
            case OAuthGrant.TryLater later -> {
                var afterRefresh = Instant.now();
                var waiting = tokens.retryingNotBefore(afterRefresh.plus(RETRY_AFTER_FAILED_RENEWAL));
                yield effects().updateState(currentState().withTokens(waiting)).thenReply(whileRenewalFails(waiting, afterRefresh));
            }
        };
    }

    public Effect<Done> begin(Pending started) {
        return effects().updateState(currentState().withPending(started, Instant.now())).thenReply(Done.getInstance());
    }

    /** Hands over the started sign-in the returning browser belongs to, once: it is forgotten whether or not it is still valid. */
    public Effect<Optional<Pending>> takePending(String state) {
        var started = currentState().pendingFor(state);
        if (started.isEmpty()) return effects().reply(Optional.empty());
        var stillValid = started.filter(candidate -> candidate.isValidAt(Instant.now()));
        return effects().updateState(currentState().withoutPending(state)).thenReply(stillValid);
    }

    public Effect<Done> connect(Tokens issued) {
        return effects().updateState(currentState().withTokens(issued)).thenReply(Done.getInstance());
    }

    public Effect<Done> disconnect() {
        return effects().updateState(OAuthTokenConnection.empty()).thenReply(Done.getInstance());
    }

    /** Replies whether the refusal disconnected the user. */
    public Effect<Boolean> rejectAccessToken(String refused) {
        if (currentState().tokens().filter(tokens -> tokens.hasAccessToken(refused)).isEmpty()) return effects().reply(false);
        var after = currentState().afterRefusing(refused, Instant.now());
        return effects().updateState(after).thenReply(after.tokens().isEmpty());
    }

    private static Bearer current(Tokens tokens) {
        return new Bearer(Outcome.CURRENT, Optional.of(tokens.accessToken()));
    }

    private static Bearer whileRenewalFails(Tokens tokens, Instant now) {
        return tokens.hasLapsed(now) ? Bearer.of(Outcome.TRY_LATER) : current(tokens);
    }

    private Effect<Bearer> forget(Outcome reason) {
        return effects().updateState(currentState().withoutTokens()).thenReply(Bearer.of(reason));
    }
}
