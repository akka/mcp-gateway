package io.akka.mcp.gateway.application;

import java.util.Optional;

/**
 * Where a token connector gets the credential it forwards as {@code Authorization: Bearer} for a user, and what
 * happens to it when the upstream refuses it or the user disconnects.
 */
public interface BearerSource {

    /** Whether the user has a credential, without reading it. Fails when the lookup fails. */
    boolean isConnected(String userId);

    /** The credential to forward, empty when the user has none. Fails when the lookup fails. */
    Optional<String> bearerFor(String userId);

    /** Forgets the credential only if it is still the one the upstream refused. Replies whether it was forgotten. */
    boolean forgetIfRefused(String userId, String refusedBearer);

    void disconnect(String userId);
}
