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

@DisplayName("JsonInvocationEventEmitter")
class JsonInvocationEventEmitterTest {

    private static final String CALLER_IDENTITY = "user:42";

    private JsonInvocationEventEmitter emitter;
    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        emitter = new JsonInvocationEventEmitter();

        logbackLogger = (Logger) LoggerFactory.getLogger(JsonInvocationEventEmitter.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
    }

    private MethodInvocationEvent successEvent() {
        return new MethodInvocationEvent(
                "UserService", "getUser", Instant.parse("2026-01-01T00:00:00Z"), 12_000_000L, true, null, null,
                CALLER_IDENTITY, null, "abc123", 2
        );
    }

    private MethodInvocationEvent failureEvent(String exceptionType, String rootCauseType) {
        return new MethodInvocationEvent(
                "UserService", "getUser", Instant.parse("2026-01-01T00:00:00Z"), 5_000_000L, false, exceptionType,
                rootCauseType, CALLER_IDENTITY, null, "abc123", 0
        );
    }

    private String printedMessage() {
        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        System.out.println("LOGGED -> " + message);
        return message;
    }

    @Nested
    @DisplayName("on a successful call")
    class SuccessfulCall {

        @Test
        @DisplayName("logs at INFO level")
        void logsAtInfoLevel() {
            emitter.emit(successEvent());

            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
        }

        @Test
        @DisplayName("writes every field as its own JSON property")
        void writesEveryFieldAsJsonProperty() {
            emitter.emit(successEvent());

            String message = printedMessage();

            assertThat(message)
                    .startsWith("{").endsWith("}")
                    .contains("\"className\":\"UserService\"")
                    .contains("\"methodName\":\"getUser\"")
                    .contains("\"timestamp\":\"2026-01-01T00:00:00Z\"")
                    .contains("\"durationMs\":12,")
                    .contains("\"success\":true")
                    .contains("\"exceptionType\":null")
                    .contains("\"rootCauseType\":null")
                    .contains("\"callerIdentity\":\"user:42\"")
                    .contains("\"callerIp\":null")
                    .contains("\"traceId\":\"abc123\"")
                    .contains("\"depth\":2");
        }
    }

    @Nested
    @DisplayName("on a failed call")
    class FailedCall {

        @Test
        @DisplayName("logs at WARN level")
        void logsAtWarnLevel() {
            emitter.emit(failureEvent("IllegalArgumentException", "IllegalArgumentException"));

            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        }

        @Test
        @DisplayName("writes exception type and root cause as JSON properties")
        void writesExceptionAndRootCause() {
            emitter.emit(failureEvent("IllegalArgumentException", "SQLException"));

            String message = printedMessage();

            assertThat(message)
                    .contains("\"exceptionType\":\"IllegalArgumentException\"")
                    .contains("\"rootCauseType\":\"SQLException\"");
        }
    }

    @Nested
    @DisplayName("string escaping")
    class StringEscaping {

        @Test
        @DisplayName("escapes quotes, backslashes, and control characters in string fields")
        void escapesSpecialCharacters() {
            MethodInvocationEvent event = new MethodInvocationEvent(
                    "Weird\"Class\\Name", "method\nWithNewline", Instant.parse("2026-01-01T00:00:00Z"),
                    1_000_000L, true, null, null, CALLER_IDENTITY, null, "abc123", 0
            );

            emitter.emit(event);

            String message = printedMessage();

            assertThat(message)
                    .contains("\"className\":\"Weird\\\"Class\\\\Name\"")
                    .contains("\"methodName\":\"method\\nWithNewline\"");
        }

        @Test
        @DisplayName("escapes control characters below U+0020 that have no dedicated shorthand")
        void escapesOtherControlCharacters() {
            String methodNameWithControlChar = "method" + '' + "WithControlChar";
            MethodInvocationEvent event = new MethodInvocationEvent(
                    "UserService", methodNameWithControlChar, Instant.parse("2026-01-01T00:00:00Z"),
                    1_000_000L, true, null, null, CALLER_IDENTITY, null, "abc123", 0
            );

            emitter.emit(event);

            String message = printedMessage();

            assertThat(message).contains("\"methodName\":\"method\\u0001WithControlChar\"");
        }

        @Test
        @DisplayName("does not escape the space character, the first non-control code point")
        void doesNotEscapeSpace() {
            MethodInvocationEvent event = new MethodInvocationEvent(
                    "UserService", "method with space", Instant.parse("2026-01-01T00:00:00Z"),
                    1_000_000L, true, null, null, CALLER_IDENTITY, null, "abc123", 0
            );

            emitter.emit(event);

            String message = printedMessage();

            assertThat(message)
                    .contains("\"methodName\":\"method with space\"")
                    .doesNotContain("\\u0020");
        }

        @Test
        @DisplayName("produces a single unbroken log line even when a field contains a newline")
        void producesSingleLogLine() {
            MethodInvocationEvent event = new MethodInvocationEvent(
                    "UserService", "method\nWithNewline", Instant.parse("2026-01-01T00:00:00Z"),
                    1_000_000L, true, null, null, CALLER_IDENTITY, null, "abc123", 0
            );

            emitter.emit(event);

            String message = printedMessage();

            assertThat(message).doesNotContain("\n");
        }
    }

    @Nested
    @DisplayName("callerIp")
    class CallerIp {

        @Test
        @DisplayName("writes the IP as its own field when present")
        void writesIpWhenPresent() {
            MethodInvocationEvent event = new MethodInvocationEvent(
                    "UserService", "getUser", Instant.parse("2026-01-01T00:00:00Z"), 12_000_000L, true, null, null,
                    CALLER_IDENTITY, "203.0.113.5", "abc123", 0
            );

            emitter.emit(event);

            String message = printedMessage();

            assertThat(message).contains("\"callerIp\":\"203.0.113.5\"");
        }
    }

    @Nested
    @DisplayName("callerDevice")
    class CallerDevice {

        private MethodInvocationEvent eventWithDevice(String callerDevice) {
            return new MethodInvocationEvent("AuthService", "login", Instant.parse("2026-01-01T00:00:00Z"),
                    12_000_000L, true, null, null, CALLER_IDENTITY, null, callerDevice, "abc123", 0);
        }

        @Test
        @DisplayName("writes callerDevice right after callerIp when present")
        void writesCallerDevice() {
            emitter.emit(eventWithDevice("Windows / Chrome 129"));

            assertThat(printedMessage())
                    .contains("\"callerIp\":null,\"callerDevice\":\"Windows / Chrome 129\",\"traceId\"");
        }

        @Test
        @DisplayName("omits callerDevice when null, keeping the pre-1.4.0 output unchanged")
        void omitsCallerDeviceWhenNull() {
            emitter.emit(eventWithDevice(null));

            assertThat(printedMessage())
                    .doesNotContain("callerDevice")
                    .contains("\"callerIp\":null,\"traceId\"");
        }
    }
}
