package io.akka.mcp.gateway.application;

import akka.stream.StreamTcpException;
import com.fasterxml.jackson.core.JsonParseException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

public class HubFailureTest {

    private static Throwable status(int code) {
        return new AkkaHttpMcpTransport.UnexpectedStatusException(code);
    }

    private static Throwable wrapped(Throwable cause) {
        return new RuntimeException("outer", new IllegalStateException("middle", cause));
    }

    @Test
    public void a401_isTheTokenBeingRejected_evenWhenWrapped() {
        assertThat(HubFailure.classify(status(401))).isEqualTo(HubFailure.TOKEN_REJECTED);
        assertThat(HubFailure.classify(wrapped(status(401)))).isEqualTo(HubFailure.TOKEN_REJECTED);
    }

    @Test
    public void serverErrors_timeoutsAndThrottling_areUnavailable() {
        assertThat(HubFailure.classify(status(500))).isEqualTo(HubFailure.UNAVAILABLE);
        assertThat(HubFailure.classify(status(503))).isEqualTo(HubFailure.UNAVAILABLE);
        assertThat(HubFailure.classify(status(408))).isEqualTo(HubFailure.UNAVAILABLE);
        assertThat(HubFailure.classify(status(429))).isEqualTo(HubFailure.UNAVAILABLE);
    }

    @Test
    public void networkFailures_areUnavailable_evenWhenWrapped() {
        assertThat(HubFailure.classify(new IOException("connection refused"))).isEqualTo(HubFailure.UNAVAILABLE);
        assertThat(HubFailure.classify(wrapped(new TimeoutException()))).isEqualTo(HubFailure.UNAVAILABLE);
    }

    @Test
    public void aStreamLevelConnectionFailure_isUnavailable() {
        assertThat(HubFailure.classify(wrapped(new StreamTcpException("connection reset")))).isEqualTo(HubFailure.UNAVAILABLE);
    }

    @Test
    public void aConnectionClosedBeforeAnAnswer_isUnavailable() {
        class UnexpectedConnectionClosureException extends RuntimeException { }

        assertThat(HubFailure.classify(wrapped(new UnexpectedConnectionClosureException()))).isEqualTo(HubFailure.UNAVAILABLE);
    }

    @Test
    public void aBodyThatCannotBeParsed_isNotANetworkFailure() {
        var parseFailure = new JsonParseException(null, "not json");

        assertThat(HubFailure.classify(wrapped(parseFailure))).isEqualTo(HubFailure.OTHER);
    }

    @Test
    public void otherRefusalsAndUnknownFailures_areOther() {
        assertThat(HubFailure.classify(status(403))).isEqualTo(HubFailure.OTHER);
        assertThat(HubFailure.classify(status(404))).isEqualTo(HubFailure.OTHER);
        assertThat(HubFailure.classify(new IllegalArgumentException("bad arguments"))).isEqualTo(HubFailure.OTHER);
    }

    @Test
    public void describe_givesTheStatusOrTheClassName_neverTheMessage() {
        assertThat(HubFailure.describe(wrapped(status(503)))).isEqualTo("HTTP 503");
        assertThat(HubFailure.describe(new IllegalStateException("secret text from the hub"))).isEqualTo("IllegalStateException");
    }
}
