package io.akka.mcp.gateway.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One user's OAuth sign-in to one connector. The access and refresh tokens are secrets: they never leave this
 * record except to the client that forwards the access token and the refresh call that renews it.
 */
public record OAuthTokenConnection(Optional<Tokens> tokens, List<Pending> pending) {

    public static final Duration PENDING_LIFETIME = Duration.ofMinutes(10);
    static final int MAX_PENDING = 3;
    private static final Duration MAX_RENEWAL_MARGIN = Duration.ofSeconds(60);
    private static final Duration RECENT = Duration.ofSeconds(30);

    /**
     * The client the gateway registered with the authorization server, the resource the tokens were issued for, and
     * the tokens themselves. A token with a refresh token is renewed shortly before it expires, a minute ahead or half
     * its lifetime ahead if that is less. After a failed renewal the next one waits until {@code retryNotBefore}.
     */
    public record Tokens(String accessToken, Optional<String> refreshToken, Instant issuedAt, Instant expiresAt, String clientId,
                         String tokenEndpoint, String resource, Optional<Instant> retryNotBefore) {

        public static Tokens issued(String accessToken, Optional<String> refreshToken, Instant issuedAt, Instant expiresAt, String clientId,
                                    String tokenEndpoint, String resource) {
            return new Tokens(accessToken, refreshToken, issuedAt, expiresAt, clientId, tokenEndpoint, resource, Optional.empty());
        }

        public Tokens renewedWith(String newAccessToken, Optional<String> newRefreshToken, Instant newIssuedAt, Instant newExpiresAt) {
            return issued(newAccessToken, newRefreshToken, newIssuedAt, newExpiresAt, clientId, tokenEndpoint, resource);
        }

        public Tokens markedExpiredAt(Instant now) {
            return new Tokens(accessToken, refreshToken, issuedAt, now, clientId, tokenEndpoint, resource, retryNotBefore);
        }

        public Tokens retryingNotBefore(Instant when) {
            return new Tokens(accessToken, refreshToken, issuedAt, expiresAt, clientId, tokenEndpoint, resource, Optional.of(when));
        }

        /** Due for renewal: close enough to expiring that the next call should renew it. */
        public boolean isExpired(Instant now) {
            return !now.plus(renewalMargin()).isBefore(expiresAt);
        }

        public boolean hasLapsedFor(Instant now, Duration atLeast) {
            return !now.isBefore(expiresAt.plus(atLeast));
        }

        /** Past its expiry, so the upstream will no longer accept it. */
        public boolean hasLapsed(Instant now) {
            return !now.isBefore(expiresAt);
        }

        private Duration renewalMargin() {
            var half = Duration.between(issuedAt, expiresAt).dividedBy(2);
            if (half.isNegative()) return Duration.ZERO;
            return half.compareTo(MAX_RENEWAL_MARGIN) < 0 ? half : MAX_RENEWAL_MARGIN;
        }

        public boolean canRefresh() {
            return refreshToken.isPresent();
        }

        public boolean isUsable(Instant now) {
            return canRefresh() || !hasLapsed(now);
        }

        public boolean isWaitingToRetry(Instant now) {
            return retryNotBefore.filter(now::isBefore).isPresent();
        }

        /** A token that was only just issued and is refused again will not be mended by renewing it. */
        public boolean isRecentlyIssued(Instant now) {
            return now.isBefore(issuedAt.plus(RECENT));
        }

        public boolean hasAccessToken(String candidate) {
            return accessToken.equals(candidate);
        }

        public boolean isFor(String wantedResource) {
            return resource.equals(wantedResource);
        }

        @Override
        public String toString() {
            return "Tokens[hidden]";
        }
    }

    /** A sign-in that was started and not yet completed; the state ties the browser's return to this user and connector. */
    public record Pending(String state, String codeVerifier, String clientId, String tokenEndpoint, String resource, Instant expiresAt) {

        public static Pending startedAt(Instant now, String state, String codeVerifier, String clientId, String tokenEndpoint, String resource) {
            return new Pending(state, codeVerifier, clientId, tokenEndpoint, resource, now.plus(PENDING_LIFETIME));
        }

        public boolean isValidAt(Instant now) {
            return now.isBefore(expiresAt);
        }

        @Override
        public String toString() {
            return "Pending[hidden]";
        }
    }

    public static OAuthTokenConnection empty() {
        return new OAuthTokenConnection(Optional.empty(), List.of());
    }

    public boolean isConnectedFor(String resource, Instant now) {
        return tokens.filter(held -> held.isFor(resource) && held.isUsable(now)).isPresent();
    }

    /** Keeps the last few started sign-ins, so a second tab or a double click does not cancel the first. */
    public OAuthTokenConnection withPending(Pending started, Instant now) {
        var all = Stream.concat(pending.stream().filter(candidate -> candidate.isValidAt(now)), Stream.of(started)).toList();
        return new OAuthTokenConnection(tokens, all.subList(Math.max(0, all.size() - MAX_PENDING), all.size()));
    }

    public Optional<Pending> pendingFor(String state) {
        return pending.stream().filter(candidate -> candidate.state().equals(state)).findFirst();
    }

    public OAuthTokenConnection withoutPending(String state) {
        return new OAuthTokenConnection(tokens, pending.stream().filter(candidate -> !candidate.state().equals(state)).toList());
    }

    public OAuthTokenConnection withTokens(Tokens held) {
        return new OAuthTokenConnection(Optional.of(held), pending);
    }

    /** Forgets the tokens but not a sign-in the user has just started. */
    public OAuthTokenConnection withoutTokens() {
        return new OAuthTokenConnection(Optional.empty(), pending);
    }

    /**
     * The upstream refused this access token. One that is not recent and can be renewed is only marked expired, so the
     * next call renews it; a recent one, or one that cannot be renewed, is forgotten.
     */
    public OAuthTokenConnection afterRefusing(String refused, Instant now) {
        var held = tokens.filter(candidate -> candidate.hasAccessToken(refused));
        if (held.isEmpty()) return this;
        var refusedTokens = held.get();
        return refusedTokens.canRefresh() && !refusedTokens.isRecentlyIssued(now) ? withTokens(refusedTokens.markedExpiredAt(now)) : withoutTokens();
    }

    @Override
    public String toString() {
        return "OAuthTokenConnection[connected=" + tokens.isPresent() + "]";
    }
}
