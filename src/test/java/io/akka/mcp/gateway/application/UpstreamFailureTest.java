package io.akka.mcp.gateway.application;

import akka.stream.StreamTcpException;
import com.fasterxml.jackson.core.JsonParseException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

public class UpstreamFailureTest {

    private static Throwable status(int code) {
        return new AkkaHttpMcpTransport.UnexpectedStatusException(code);
    }

    private static Throwable wrapped(Throwable cause) {
        return new RuntimeException("outer", new IllegalStateException("middle", cause));
    }

    @Test
    public void a401_isTheTokenBeingRejected_evenWhenWrapped() {
        assertThat(UpstreamFailure.classify(status(401))).isEqualTo(UpstreamFailure.TOKEN_REJECTED);
        assertThat(UpstreamFailure.classify(wrapped(status(401)))).isEqualTo(UpstreamFailure.TOKEN_REJECTED);
    }

    @Test
    public void serverErrors_timeoutsAndThrottling_areUnavailable() {
        assertThat(UpstreamFailure.classify(status(500))).isEqualTo(UpstreamFailure.UNAVAILABLE);
        assertThat(UpstreamFailure.classify(status(503))).isEqualTo(UpstreamFailure.UNAVAILABLE);
        assertThat(UpstreamFailure.classify(status(408))).isEqualTo(UpstreamFailure.UNAVAILABLE);
        assertThat(UpstreamFailure.classify(status(429))).isEqualTo(UpstreamFailure.UNAVAILABLE);
    }

    @Test
    public void networkFailures_areUnavailable_evenWhenWrapped() {
        assertThat(UpstreamFailure.classify(new IOException("connection refused"))).isEqualTo(UpstreamFailure.UNAVAILABLE);
        assertThat(UpstreamFailure.classify(wrapped(new TimeoutException()))).isEqualTo(UpstreamFailure.UNAVAILABLE);
    }

    @Test
    public void aStreamLevelConnectionFailure_isUnavailable() {
        assertThat(UpstreamFailure.classify(wrapped(new StreamTcpException("connection reset")))).isEqualTo(UpstreamFailure.UNAVAILABLE);
    }

    @Test
    public void aConnectionClosedBeforeAnAnswer_isUnavailable() {
        class UnexpectedConnectionClosureException extends RuntimeException { }

        assertThat(UpstreamFailure.classify(wrapped(new UnexpectedConnectionClosureException()))).isEqualTo(UpstreamFailure.UNAVAILABLE);
    }

    @Test
    public void aBodyThatCannotBeParsed_isNotANetworkFailure() {
        var parseFailure = new JsonParseException(null, "not json");

        assertThat(UpstreamFailure.classify(wrapped(parseFailure))).isEqualTo(UpstreamFailure.OTHER);
    }

    @Test
    public void otherRefusalsAndUnknownFailures_areOther() {
        assertThat(UpstreamFailure.classify(status(403))).isEqualTo(UpstreamFailure.OTHER);
        assertThat(UpstreamFailure.classify(status(404))).isEqualTo(UpstreamFailure.OTHER);
        assertThat(UpstreamFailure.classify(new IllegalArgumentException("bad arguments"))).isEqualTo(UpstreamFailure.OTHER);
    }

    @Test
    public void describe_givesTheStatusOrTheClassName_neverTheMessage() {
        assertThat(UpstreamFailure.describe(wrapped(status(503)))).isEqualTo("HTTP 503");
        assertThat(UpstreamFailure.describe(new IllegalStateException("secret text from the upstream"))).isEqualTo("IllegalStateException");
        assertThat(UpstreamFailure.describe(new RuntimeException("outer text", new java.io.IOException("inner text")))).isEqualTo("IOException");
    }
}
