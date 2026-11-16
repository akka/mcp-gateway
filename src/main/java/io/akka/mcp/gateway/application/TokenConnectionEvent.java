package io.akka.mcp.gateway.application;

/** What the interaction history records about a user's connection to a token connector, whether they paste a token or sign in. */
public enum TokenConnectionEvent {
    CONNECT_BLOCKED("connect-blocked"),
    CONNECT_ATTEMPT("connect-attempt"),
    CONNECT_SUCCESS("connect-success"),
    CONNECT_FAILED("connect-failed"),
    DISCONNECT("disconnect");

    private final String direction;

    TokenConnectionEvent(String direction) {
        this.direction = direction;
    }

    public String direction() {
        return direction;
    }
}
