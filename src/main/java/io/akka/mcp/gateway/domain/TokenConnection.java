package io.akka.mcp.gateway.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Optional;

/** One user's pasted token for one connector. The token is a secret: it never leaves this record except to the client that forwards it. */
public record TokenConnection(Optional<String> token) {

    public static final int MAX_TOKEN_LENGTH = 4096;

    public static TokenConnection empty() {
        return new TokenConnection(Optional.empty());
    }

    @JsonIgnore
    public boolean isConnected() {
        return token.isPresent();
    }

    public TokenConnection withToken(String newToken) {
        return new TokenConnection(Optional.of(newToken));
    }

    public TokenConnection disconnected() {
        return empty();
    }

    /** Printable ASCII with no whitespace, so a token can never smuggle a second header into the forwarded request. */
    public static boolean isWellFormedToken(String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.length() > MAX_TOKEN_LENGTH) return false;
        return candidate.chars().allMatch(c -> c > 0x20 && c < 0x7f);
    }

    @Override
    public String toString() {
        return "TokenConnection[connected=" + isConnected() + "]";
    }
}
