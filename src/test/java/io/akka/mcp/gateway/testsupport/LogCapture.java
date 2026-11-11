package io.akka.mcp.gateway.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.function.Executable;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Everything written to any log, at any level, while an action runs: each message with its stack trace. */
public final class LogCapture {

    private LogCapture() {}

    public static List<String> during(Executable action) throws Throwable {
        var allLogging = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var previousLevel = allLogging.getLevel();
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        allLogging.addAppender(captured);
        allLogging.setLevel(Level.DEBUG);
        try {
            action.execute();
        } finally {
            allLogging.detachAppender(captured);
            allLogging.setLevel(previousLevel);
            captured.stop();
        }
        assertThat(captured.list).isNotEmpty();
        return List.copyOf(captured.list).stream()
                .map(event -> event.getFormattedMessage()
                        + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .toList();
    }
}
