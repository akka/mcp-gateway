package io.akka.mcp.gateway.application;

import java.util.Optional;

/**
 * Where a token connector gets the credential it forwards as {@code Authorization: Bearer} for a user, and what
 * happens to it when the upstream refuses it or the user disconnects.
 */
public interface BearerSource {

    /** The user has no credential. */
    class NotConnected extends RuntimeException {
        public NotConnected(String message) {
            super(message);
        }
    }

    /** The user's credential just ended and has been forgotten, for the reason given. */
    class SignInEnded extends NotConnected {
        public SignInEnded(String reason) {
            super(reason);
        }
    }

    /** The credential could not be looked up or renewed right now, and may be tried again shortly. */
    class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    /** Whether the user has a credential, without reading it. Fails when the lookup fails. */
    boolean isConnected(String userId);

    /**
     * The credential to forward, empty when the user has none. Fails with {@link SignInEnded} when it ended just
     * now, with {@link Unavailable} when it could not be renewed, and otherwise when the lookup fails.
     */
    Optional<String> bearerFor(String userId);

    /** Forgets the credential only if it is still the one the upstream refused. Replies whether it was forgotten. */
    boolean forgetIfRefused(String userId, String refusedBearer);

    void disconnect(String userId);
}
