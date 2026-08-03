package com.fayupable.logged.spring.kafka;

import com.fayupable.logged.core.model.FlowContext;
import com.fayupable.logged.spring.aspect.FlowContextCarrier;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KafkaTraceHeaderCarrier")
class KafkaTraceHeaderCarrierTest {

    @Nested
    @DisplayName("writeToHeaders()")
    class WriteToHeaders {

        @Test
        @DisplayName("writes the active trace id and depth as headers")
        void writesActiveContext() {
            RecordHeaders headers = new RecordHeaders();
            FlowContext active = new FlowContext("outer-trace", 2);
            Runnable restore = FlowContextCarrier.adopt(active);
            try {
                KafkaTraceHeaderCarrier.writeToHeaders(headers);

                String traceId = new String(headers.lastHeader("logged-traceId").value(), StandardCharsets.UTF_8);
                String depth = new String(headers.lastHeader("logged-depth").value(), StandardCharsets.UTF_8);

                assertThat(traceId).isEqualTo("outer-trace");
                assertThat(depth).isEqualTo("2");
            } finally {
                restore.run();
            }
        }

        @Test
        @DisplayName("writes nothing when no call chain is active on this thread")
        void writesNothingWhenNoContextActive() {
            RecordHeaders headers = new RecordHeaders();

            KafkaTraceHeaderCarrier.writeToHeaders(headers);

            assertThat(headers.lastHeader("logged-traceId")).isNull();
            assertThat(headers.lastHeader("logged-depth")).isNull();
        }
    }

    @Nested
    @DisplayName("readAndAdopt()")
    class ReadAndAdopt {

        @Test
        @DisplayName("adopts the trace id and depth carried by the headers for the duration of the work")
        void adoptsContextFromHeaders() {
            RecordHeaders headers = new RecordHeaders();
            headers.add("logged-traceId", "abc123".getBytes(StandardCharsets.UTF_8));
            headers.add("logged-depth", "3".getBytes(StandardCharsets.UTF_8));
            AtomicReference<String> observedTraceId = new AtomicReference<>();
            AtomicReference<Integer> observedDepth = new AtomicReference<>();

            KafkaTraceHeaderCarrier.readAndAdopt(headers, () -> {
                FlowContext active = FlowContextCarrier.capture();
                observedTraceId.set(active.traceId());
                observedDepth.set(active.depth());
            });

            assertThat(observedTraceId.get()).isEqualTo("abc123");
            assertThat(observedDepth.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("runs the work directly, adopting nothing, when headers carry no trace id")
        void runsWorkAsIsWhenNoTraceIdHeader() {
            RecordHeaders headers = new RecordHeaders();
            AtomicReference<Boolean> ran = new AtomicReference<>(false);
            AtomicReference<FlowContext> observedContext = new AtomicReference<>(new FlowContext("sentinel", 0));

            KafkaTraceHeaderCarrier.readAndAdopt(headers, () -> {
                ran.set(true);
                observedContext.set(FlowContextCarrier.capture());
            });

            assertThat(ran.get()).isTrue();
            assertThat(observedContext.get()).isNull();
        }

        @Test
        @DisplayName("runs the work directly, adopting nothing, when the trace id header does not look like a real trace id")
        void runsWorkAsIsWhenTraceIdHeaderIsMalformed() {
            RecordHeaders headers = new RecordHeaders();
            headers.add("logged-traceId", "abc123\n10:00:00 INFO FakeService - all clear".getBytes(StandardCharsets.UTF_8));
            AtomicReference<FlowContext> observedContext = new AtomicReference<>(new FlowContext("sentinel", 0));

            KafkaTraceHeaderCarrier.readAndAdopt(headers, () -> observedContext.set(FlowContextCarrier.capture()));

            assertThat(observedContext.get()).isNull();
        }

        @Test
        @DisplayName("defaults depth to 0 when the depth header is missing")
        void defaultsDepthToZeroWhenMissing() {
            RecordHeaders headers = new RecordHeaders();
            headers.add("logged-traceId", "abc123".getBytes(StandardCharsets.UTF_8));
            AtomicReference<Integer> observedDepth = new AtomicReference<>();

            KafkaTraceHeaderCarrier.readAndAdopt(headers, () -> observedDepth.set(FlowContextCarrier.capture().depth()));

            assertThat(observedDepth.get()).isZero();
        }

        @Test
        @DisplayName("restores this thread's previous context once the work completes")
        void restoresPreviousContextAfterWork() {
            RecordHeaders headers = new RecordHeaders();
            headers.add("logged-traceId", "abc123".getBytes(StandardCharsets.UTF_8));
            headers.add("logged-depth", "1".getBytes(StandardCharsets.UTF_8));

            FlowContext outerContext = new FlowContext("outer-trace", 5);
            Runnable restoreOuter = FlowContextCarrier.adopt(outerContext);
            try {
                KafkaTraceHeaderCarrier.readAndAdopt(headers, () -> { });

                assertThat(FlowContextCarrier.capture()).isEqualTo(outerContext);
            } finally {
                restoreOuter.run();
            }
        }

        @Test
        @DisplayName("still restores this thread's previous context when the work throws")
        void restoresPreviousContextWhenWorkThrows() {
            RecordHeaders headers = new RecordHeaders();
            headers.add("logged-traceId", "abc123".getBytes(StandardCharsets.UTF_8));
            headers.add("logged-depth", "1".getBytes(StandardCharsets.UTF_8));

            FlowContext outerContext = new FlowContext("outer-trace", 5);
            Runnable restoreOuter = FlowContextCarrier.adopt(outerContext);
            try {
                try {
                    KafkaTraceHeaderCarrier.readAndAdopt(headers, () -> {
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
