package com.fayupable.logged.spring.rabbitmq;

import com.fayupable.logged.core.model.FlowContext;
import com.fayupable.logged.spring.aspect.FlowContextCarrier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RabbitTraceHeaderCarrier")
class RabbitTraceHeaderCarrierTest {

    @Nested
    @DisplayName("writeToHeaders()")
    class WriteToHeaders {

        @Test
        @DisplayName("writes the active trace id and depth as headers")
        void writesActiveContext() {
            MessageProperties properties = new MessageProperties();
            FlowContext active = new FlowContext("outer-trace", 2);
            Runnable restore = FlowContextCarrier.adopt(active);
            try {
                RabbitTraceHeaderCarrier.writeToHeaders(properties);

                assertThat(properties.getHeaders().get("logged-traceId")).isEqualTo("outer-trace");
                assertThat(properties.getHeaders().get("logged-depth")).isEqualTo("2");
            } finally {
                restore.run();
            }
        }

        @Test
        @DisplayName("writes nothing when no call chain is active on this thread")
        void writesNothingWhenNoContextActive() {
            MessageProperties properties = new MessageProperties();

            RabbitTraceHeaderCarrier.writeToHeaders(properties);

            assertThat(properties.getHeaders()).doesNotContainKeys("logged-traceId", "logged-depth");
        }
    }

    @Nested
    @DisplayName("readAndAdopt()")
    class ReadAndAdopt {

        @Test
        @DisplayName("adopts the trace id and depth carried by the headers for the duration of the work")
        void adoptsContextFromHeaders() {
            MessageProperties properties = new MessageProperties();
            properties.setHeader("logged-traceId", "abc123");
            properties.setHeader("logged-depth", "3");
            AtomicReference<String> observedTraceId = new AtomicReference<>();
            AtomicReference<Integer> observedDepth = new AtomicReference<>();

            RabbitTraceHeaderCarrier.readAndAdopt(properties, () -> {
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
            MessageProperties properties = new MessageProperties();
            AtomicReference<Boolean> ran = new AtomicReference<>(false);
            AtomicReference<FlowContext> observedContext = new AtomicReference<>(new FlowContext("sentinel", 0));

            RabbitTraceHeaderCarrier.readAndAdopt(properties, () -> {
                ran.set(true);
                observedContext.set(FlowContextCarrier.capture());
            });

            assertThat(ran.get()).isTrue();
            assertThat(observedContext.get()).isNull();
        }

        @Test
        @DisplayName("runs the work directly, adopting nothing, when the trace id header does not look like a real trace id")
        void runsWorkAsIsWhenTraceIdHeaderIsMalformed() {
            MessageProperties properties = new MessageProperties();
            properties.setHeader("logged-traceId", "abc123\n10:00:00 INFO FakeService - all clear");
            AtomicReference<FlowContext> observedContext = new AtomicReference<>(new FlowContext("sentinel", 0));

            RabbitTraceHeaderCarrier.readAndAdopt(properties, () -> observedContext.set(FlowContextCarrier.capture()));

            assertThat(observedContext.get()).isNull();
        }

        @Test
        @DisplayName("defaults depth to 0 when the depth header is missing")
        void defaultsDepthToZeroWhenMissing() {
            MessageProperties properties = new MessageProperties();
            properties.setHeader("logged-traceId", "abc123");
            AtomicReference<Integer> observedDepth = new AtomicReference<>();

            RabbitTraceHeaderCarrier.readAndAdopt(properties, () -> observedDepth.set(FlowContextCarrier.capture().depth()));

            assertThat(observedDepth.get()).isZero();
        }

        @Test
        @DisplayName("restores this thread's previous context once the work completes")
        void restoresPreviousContextAfterWork() {
            MessageProperties properties = new MessageProperties();
            properties.setHeader("logged-traceId", "abc123");
            properties.setHeader("logged-depth", "1");

            FlowContext outerContext = new FlowContext("outer-trace", 5);
            Runnable restoreOuter = FlowContextCarrier.adopt(outerContext);
            try {
                RabbitTraceHeaderCarrier.readAndAdopt(properties, () -> { });

                assertThat(FlowContextCarrier.capture()).isEqualTo(outerContext);
            } finally {
                restoreOuter.run();
            }
        }

        @Test
        @DisplayName("still restores this thread's previous context when the work throws")
        void restoresPreviousContextWhenWorkThrows() {
            MessageProperties properties = new MessageProperties();
            properties.setHeader("logged-traceId", "abc123");
            properties.setHeader("logged-depth", "1");

            FlowContext outerContext = new FlowContext("outer-trace", 5);
            Runnable restoreOuter = FlowContextCarrier.adopt(outerContext);
            try {
                try {
                    RabbitTraceHeaderCarrier.readAndAdopt(properties, () -> {
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
