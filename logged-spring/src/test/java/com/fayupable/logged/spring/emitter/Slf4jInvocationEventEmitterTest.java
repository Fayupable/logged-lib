package com.fayupable.logged.spring.emitter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fayupable.logged.core.model.MethodInvocationEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Slf4jInvocationEventEmitter")
class Slf4jInvocationEventEmitterTest {

    private static final String CALLER_IDENTITY = "user:42";

    private Slf4jInvocationEventEmitter emitter;
    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        emitter = new Slf4jInvocationEventEmitter();

        logbackLogger = (Logger) LoggerFactory.getLogger(Slf4jInvocationEventEmitter.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
    }

    private MethodInvocationEvent successEvent(int depth) {
        return new MethodInvocationEvent(
                "UserService", "getUser", Instant.now(), 12_000_000L, true, null, CALLER_IDENTITY, "abc123", depth
        );
    }

    private MethodInvocationEvent failureEvent(int depth) {
        return new MethodInvocationEvent(
                "UserService", "getUser", Instant.now(), 5_000_000L, false, "IllegalArgumentException",
                CALLER_IDENTITY, "abc123", depth
        );
    }

    private String printedMessage() {
        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        System.out.println("LOGGED -> " + message);
        return message;
    }

    @Nested
    @DisplayName("on a successful, non-nested call (depth = 0)")
    class SuccessfulRootCall {

        @Test
        @DisplayName("logs at INFO level")
        void logsAtInfoLevel() {
            emitter.emit(successEvent(0));

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
        }

        @Test
        @DisplayName("includes caller identity, class name, method name, and duration")
        void includesCoreFields() {
            emitter.emit(successEvent(0));

            String message = printedMessage();

            assertThat(message)
                    .contains(CALLER_IDENTITY)
                    .contains("UserService")
                    .contains("getUser")
                    .contains("12ms");
        }

        @Test
        @DisplayName("does not include trace id or depth")
        void omitsChainDetails() {
            emitter.emit(successEvent(0));

            String message = printedMessage();

            assertThat(message).doesNotContain("trace=").doesNotContain("depth=");
        }
    }

    @Nested
    @DisplayName("on a successful, nested call (depth > 0)")
    class SuccessfulNestedCall {

        @Test
        @DisplayName("includes trace id and depth")
        void includesChainDetails() {
            emitter.emit(successEvent(2));

            String message = printedMessage();

            assertThat(message).contains("trace=abc123").contains("depth=2");
        }
    }

    @Nested
    @DisplayName("on a failed call")
    class FailedCall {

        @Test
        @DisplayName("logs at WARN level")
        void logsAtWarnLevel() {
            emitter.emit(failureEvent(0));

            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        }

        @Test
        @DisplayName("includes the exception's simple class name")
        void includesExceptionType() {
            emitter.emit(failureEvent(0));

            String message = printedMessage();

            assertThat(message).contains("IllegalArgumentException");
        }
    }
}
