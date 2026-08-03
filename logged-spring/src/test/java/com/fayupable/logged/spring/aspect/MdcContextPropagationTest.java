package com.fayupable.logged.spring.aspect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MdcContextPropagation")
class MdcContextPropagationTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Nested
    @DisplayName("capture()")
    class Capture {

        @Test
        @DisplayName("returns null when the MDC is empty")
        void returnsNullWhenEmpty() {
            assertThat(MdcContextPropagation.capture()).isNull();
        }

        @Test
        @DisplayName("returns every entry currently in the MDC")
        void returnsAllEntries() {
            MDC.put("requestId", "req-123");
            MDC.put(LoggedMdcKeys.TRACE_ID, "trace-abc");

            Map<String, String> captured = MdcContextPropagation.capture();

            assertThat(captured).containsEntry("requestId", "req-123")
                    .containsEntry(LoggedMdcKeys.TRACE_ID, "trace-abc");
        }
    }

    @Nested
    @DisplayName("adopt()")
    class Adopt {

        @Test
        @DisplayName("makes the given map the active MDC context")
        void makesMapActive() {
            Map<String, String> context = Map.of("requestId", "req-999");

            Runnable restore = MdcContextPropagation.adopt(context);
            try {
                assertThat(MDC.get("requestId")).isEqualTo("req-999");
            } finally {
                restore.run();
            }
        }

        @Test
        @DisplayName("clears the MDC when adopting null")
        void clearsMdcWhenAdoptingNull() {
            MDC.put("requestId", "req-999");

            Runnable restore = MdcContextPropagation.adopt(null);
            try {
                assertThat(MDC.getCopyOfContextMap()).isNull();
            } finally {
                restore.run();
            }
        }

        @Test
        @DisplayName("restores the previously active map once the returned Runnable is run")
        void restoresPreviousMapOnRun() {
            MDC.put("requestId", "req-999");

            Runnable restore = MdcContextPropagation.adopt(Map.of("requestId", "borrowed"));
            restore.run();

            assertThat(MDC.get("requestId")).isEqualTo("req-999");
        }

        @Test
        @DisplayName("clears the MDC once the returned Runnable is run, when nothing was active before")
        void clearsMdcOnRunWhenNothingWasActiveBefore() {
            Runnable restore = MdcContextPropagation.adopt(Map.of("requestId", "borrowed"));
            restore.run();

            assertThat(MDC.getCopyOfContextMap()).isNull();
        }
    }
}
