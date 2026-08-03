package com.fayupable.logged.spring.http;

import com.fayupable.logged.core.model.FlowContext;
import com.fayupable.logged.spring.aspect.FlowContextCarrier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HttpTraceHeaderCarrier")
class HttpTraceHeaderCarrierTest {

    @Nested
    @DisplayName("writeToHeaders()")
    class WriteToHeaders {

        @Test
        @DisplayName("writes the active trace id and depth through the given header writer")
        void writesActiveContext() {
            Map<String, String> headers = new HashMap<>();
            FlowContext active = new FlowContext("outer-trace", 2);
            Runnable restore = FlowContextCarrier.adopt(active);
            try {
                HttpTraceHeaderCarrier.writeToHeaders(headers::put);

                assertThat(headers).containsEntry("X-Logged-Trace-Id", "outer-trace");
                assertThat(headers).containsEntry("X-Logged-Depth", "2");
            } finally {
                restore.run();
            }
        }

        @Test
        @DisplayName("writes nothing when no call chain is active on this thread")
        void writesNothingWhenNoContextActive() {
            Map<String, String> headers = new HashMap<>();

            HttpTraceHeaderCarrier.writeToHeaders(headers::put);

            assertThat(headers).isEmpty();
        }
    }

    @Nested
    @DisplayName("readAndAdopt()")
    class ReadAndAdopt {

        @Test
        @DisplayName("adopts the trace id and depth read through the given header reader for the duration of the work")
        void adoptsContextFromHeaders() {
            Map<String, String> headers = Map.of("X-Logged-Trace-Id", "abc123", "X-Logged-Depth", "3");
            AtomicReference<String> observedTraceId = new AtomicReference<>();
            AtomicReference<Integer> observedDepth = new AtomicReference<>();

            HttpTraceHeaderCarrier.readAndAdopt(headers::get, () -> {
                FlowContext active = FlowContextCarrier.capture();
                observedTraceId.set(active.traceId());
                observedDepth.set(active.depth());
            });

            assertThat(observedTraceId.get()).isEqualTo("abc123");
            assertThat(observedDepth.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("runs the work directly, adopting nothing, when no trace id header is present")
        void runsWorkAsIsWhenNoTraceIdHeader() {
            Map<String, String> headers = Map.of();
            AtomicReference<Boolean> ran = new AtomicReference<>(false);
            AtomicReference<FlowContext> observedContext = new AtomicReference<>(new FlowContext("sentinel", 0));

            HttpTraceHeaderCarrier.readAndAdopt(headers::get, () -> {
                ran.set(true);
                observedContext.set(FlowContextCarrier.capture());
            });

            assertThat(ran.get()).isTrue();
            assertThat(observedContext.get()).isNull();
        }

        @Test
        @DisplayName("runs the work directly, adopting nothing, when the trace id header does not look like a real trace id")
        void runsWorkAsIsWhenTraceIdHeaderIsMalformed() {
            Map<String, String> headers = Map.of("X-Logged-Trace-Id", "abc123\n10:00:00 INFO FakeService - all clear");
            AtomicReference<FlowContext> observedContext = new AtomicReference<>(new FlowContext("sentinel", 0));

            HttpTraceHeaderCarrier.readAndAdopt(headers::get, () -> observedContext.set(FlowContextCarrier.capture()));

            assertThat(observedContext.get()).isNull();
        }

        @Test
        @DisplayName("defaults depth to 0 when the depth header is missing")
        void defaultsDepthToZeroWhenMissing() {
            Map<String, String> headers = Map.of("X-Logged-Trace-Id", "abc123");
            AtomicReference<Integer> observedDepth = new AtomicReference<>();

            HttpTraceHeaderCarrier.readAndAdopt(headers::get, () -> observedDepth.set(FlowContextCarrier.capture().depth()));

            assertThat(observedDepth.get()).isZero();
        }

        @Test
        @DisplayName("restores this thread's previous context once the work completes")
        void restoresPreviousContextAfterWork() {
            Map<String, String> headers = Map.of("X-Logged-Trace-Id", "abc123", "X-Logged-Depth", "1");
            FlowContext outerContext = new FlowContext("outer-trace", 5);
            Runnable restoreOuter = FlowContextCarrier.adopt(outerContext);
            try {
                HttpTraceHeaderCarrier.readAndAdopt(headers::get, () -> { });

                assertThat(FlowContextCarrier.capture()).isEqualTo(outerContext);
            } finally {
                restoreOuter.run();
            }
        }

        @Test
        @DisplayName("still restores this thread's previous context when the work throws")
        void restoresPreviousContextWhenWorkThrows() {
            Map<String, String> headers = Map.of("X-Logged-Trace-Id", "abc123", "X-Logged-Depth", "1");
            FlowContext outerContext = new FlowContext("outer-trace", 5);
            Runnable restoreOuter = FlowContextCarrier.adopt(outerContext);
            try {
                try {
                    HttpTraceHeaderCarrier.readAndAdopt(headers::get, () -> {
                        throw new IllegalStateException("boom");
                    });
                } catch (IllegalStateException ignored) {
                    // expected
                }

                assertThat(FlowContextCarrier.capture()).isEqualTo(outerContext);
            } finally {
                restoreOuter.run();
            }
        }
    }
}
