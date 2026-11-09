package io.akka.mcp.gateway.application;

import akka.stream.StreamTcpException;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/** Why a request to the Customer Hub failed, as far as the user can act on it. */
public enum HubFailure {
    /** The hub does not accept the token: expired, revoked or wrong. */
    TOKEN_REJECTED,
    /** The hub could not be reached or answered with a failure worth retrying. */
    UNAVAILABLE,
    /** Anything else; nothing the user can do about it. */
    OTHER;

    private static final int UNAUTHORIZED = 401;
    private static final int REQUEST_TIMEOUT = 408;
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int SERVER_ERROR = 500;

    public static HubFailure classify(Throwable failure) {
        var status = statusOf(failure);
        if (status.filter(code -> code == UNAUTHORIZED).isPresent()) return TOKEN_REJECTED;
        if (status.filter(HubFailure::isRetryable).isPresent() || hasNetworkCause(failure)) return UNAVAILABLE;
        return OTHER;
    }

    /** What an operator needs from a log line, without anything the hub said. */
    public static String describe(Throwable failure) {
        return statusOf(failure)
                .map(code -> "HTTP " + code)
                .orElseGet(() -> failure.getClass().getSimpleName());
    }

    private static boolean isRetryable(int status) {
        return status >= SERVER_ERROR || status == REQUEST_TIMEOUT || status == TOO_MANY_REQUESTS;
    }

    private static Optional<Integer> statusOf(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof AkkaHttpMcpTransport.UnexpectedStatusException e) return Optional.of(e.status());
        }
        return Optional.empty();
    }

    private static boolean isNetworkFailure(Throwable t) {
        return (t instanceof IOException && !(t instanceof JsonProcessingException))
                || t instanceof TimeoutException
                || t instanceof StreamTcpException
                || isConnectionClosedBeforeAnswer(t);
    }

    private static boolean isConnectionClosedBeforeAnswer(Throwable t) {
        return t.getClass().getName().endsWith("UnexpectedConnectionClosureException");
    }

    private static boolean hasNetworkCause(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (isNetworkFailure(t)) return true;
        }
        return false;
    }
}
