package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.annotation.Logged;
import com.fayupable.logged.core.model.MethodInvocationEvent;
import com.fayupable.logged.core.port.IClientInfoPort;
import com.fayupable.logged.core.port.InvocationEventEmitter;
import com.fayupable.logged.core.port.MetricsRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LoggedAspect")
class LoggedAspectTest {

    private static final String CALLER_IDENTITY = "user:42";

    private RecordingEventEmitter eventEmitter;
    private RecordingMetricsRecorder metricsRecorder;
    private LoggedAspect aspect;

    @BeforeEach
    void setUp() {
        eventEmitter = new RecordingEventEmitter();
        metricsRecorder = new RecordingMetricsRecorder();
        aspect = new LoggedAspect(eventEmitter, metricsRecorder, () -> CALLER_IDENTITY);
    }

    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    @Nested
    @DisplayName("on a successful call")
    class SuccessfulCall {

        @Test
        @DisplayName("emits an event with success = true and no exception type")
        void emitsSuccessfulEvent() {
            Calculator calculator = proxy(new CalculatorImpl());

            int result = calculator.add(2, 3);

            System.out.println("add(2, 3) returned: " + result);
            eventEmitter.printAll();

            assertThat(result).isEqualTo(5);
            assertThat(eventEmitter.events).hasSize(1);

            MethodInvocationEvent event = eventEmitter.events.get(0);
            assertThat(event.success()).isTrue();
            assertThat(event.exceptionType()).isNull();
            assertThat(event.methodName()).isEqualTo("add");
        }

        @Test
        @DisplayName("resolves the caller identity through IClientInfoPort")
        void resolvesCallerIdentity() {
            Calculator calculator = proxy(new CalculatorImpl());

            calculator.add(1, 1);

            eventEmitter.printAll();

            assertThat(eventEmitter.events.get(0).callerIdentity()).isEqualTo(CALLER_IDENTITY);
        }

        @Test
        @DisplayName("always records a metric, regardless of sampling or threshold")
        void alwaysRecordsMetrics() {
            Calculator calculator = proxy(new CalculatorImpl());

            calculator.add(1, 1);

            metricsRecorder.printAll();

            assertThat(metricsRecorder.recordings).hasSize(1);
            assertThat(metricsRecorder.recordings.get(0).success()).isTrue();
        }
    }

    @Nested
    @DisplayName("on a failed call")
    class FailedCall {

        @Test
        @DisplayName("emits an event with success = false and the exception's simple class name")
        void emitsFailedEvent() {
            Calculator calculator = proxy(new CalculatorImpl());

            try {
                calculator.divide(10, 0);
            } catch (ArithmeticException expected) {
                System.out.println("Caught expected exception: " + expected.getMessage());
            }

            eventEmitter.printAll();

            assertThat(eventEmitter.events).hasSize(1);
            MethodInvocationEvent event = eventEmitter.events.get(0);
            assertThat(event.success()).isFalse();
            assertThat(event.exceptionType()).isEqualTo("ArithmeticException");
        }

        @Test
        @DisplayName("never captures the exception message")
        void neverCapturesExceptionMessage() {
            Calculator calculator = proxy(new CalculatorImpl());

            try {
                calculator.divide(10, 0);
            } catch (ArithmeticException ignored) {
                // expected
            }

            MethodInvocationEvent event = eventEmitter.events.get(0);
            System.out.println("Captured event: " + event);

            assertThat(event.toString()).doesNotContain("by zero");
        }

        @Test
        @DisplayName("still propagates the original exception to the caller")
        void propagatesException() {
            Calculator calculator = proxy(new CalculatorImpl());

            assertThat(catchThrowable(() -> calculator.divide(10, 0)))
                    .isInstanceOf(ArithmeticException.class);
        }

        private Throwable catchThrowable(ThrowingRunnable runnable) {
            try {
                runnable.run();
                return null;
            } catch (Throwable t) {
                return t;
            }
        }

        interface ThrowingRunnable {
            void run() throws Throwable;
        }
    }

    @Nested
    @DisplayName("slow threshold")
    class SlowThreshold {

        @Test
        @DisplayName("emits a slow, successful call even with sampling fully disabled")
        void slowCallIsEmittedRegardlessOfSampling() {
            Calculator calculator = proxy(new SlowCalculatorImpl());

            calculator.add(2, 3);

            eventEmitter.printAll();

            assertThat(eventEmitter.events).hasSize(1);
            assertThat(eventEmitter.events.get(0).success()).isTrue();
        }
    }

    @Nested
    @DisplayName("sampling")
    class Sampling {

        @Test
        @DisplayName("does not emit a fast, successful call when sampleRate is 0.0")
        void fastCallNotEmittedWhenSamplingDisabled() {
            Calculator calculator = proxy(new UnsampledCalculatorImpl());

            calculator.add(1, 1);

            System.out.println("Events emitted with sampleRate=0.0: " + eventEmitter.events.size());

            assertThat(eventEmitter.events).isEmpty();
            assertThat(metricsRecorder.recordings).hasSize(1);
        }

        @Test
        @DisplayName("emits a fast, successful call when sampleRate is 1.0")
        void fastCallEmittedWhenFullySampled() {
            Calculator calculator = proxy(new CalculatorImpl());

            calculator.add(1, 1);

            eventEmitter.printAll();

            assertThat(eventEmitter.events).hasSize(1);
        }
    }

    @Nested
    @DisplayName("call chain")
    class CallChain {

        @Test
        @DisplayName("shares the same trace id and increases depth across nested @Logged beans")
        void tracksNestedCallsAsOneChain() {
            ServiceB serviceB = proxy(new ServiceBImpl());
            ServiceA serviceA = proxy(new ServiceAImpl(serviceB));

            serviceA.process();

            eventEmitter.printAll();

            assertThat(eventEmitter.events).hasSize(2);
            MethodInvocationEvent outer = eventEmitter.events.get(1);
            MethodInvocationEvent inner = eventEmitter.events.get(0);

            System.out.println("outer=" + outer);
            System.out.println("inner=" + inner);

            assertThat(outer.methodName()).isEqualTo("process");
            assertThat(inner.methodName()).isEqualTo("doWork");

            assertThat(outer.traceId()).isEqualTo(inner.traceId());
            assertThat(outer.depth()).isZero();
            assertThat(inner.depth()).isEqualTo(1);
        }

        @Test
        @DisplayName("cleans up the thread's flow context between two separate, non-nested top-level calls")
        void cleansUpContextBetweenSeparateTopLevelCalls() {
            Calculator calculator = proxy(new CalculatorImpl());

            calculator.add(1, 1);
            calculator.add(2, 2);

            eventEmitter.printAll();

            assertThat(eventEmitter.events).hasSize(2);
            MethodInvocationEvent first = eventEmitter.events.get(0);
            MethodInvocationEvent second = eventEmitter.events.get(1);

            System.out.println("first=" + first);
            System.out.println("second=" + second);

            assertThat(first.depth()).isZero();
            assertThat(second.depth()).isZero();
            assertThat(second.traceId()).isNotEqualTo(first.traceId());
        }

        @ParameterizedTest(name = "a chain of {0} nested @Logged beans shares one trace id with sequential depths")
        @ValueSource(ints = {3, 5, 7})
        @DisplayName("supports chains deeper than two levels")
        void tracksDeeperChainsAsOneChain(int chainLength) {
            ChainLink outermost = buildChain(chainLength);

            outermost.run();

            eventEmitter.printAll();
            eventEmitter.events.forEach(event -> System.out.println("chain(" + chainLength + ") -> " + event));

            assertThat(eventEmitter.events).hasSize(chainLength);

            List<String> distinctTraceIds = eventEmitter.events.stream()
                    .map(MethodInvocationEvent::traceId)
                    .distinct()
                    .collect(Collectors.toList());
            assertThat(distinctTraceIds).hasSize(1);

            List<Integer> orderedDepths = eventEmitter.events.stream()
                    .map(MethodInvocationEvent::depth)
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());
            List<Integer> expectedDepths = java.util.stream.IntStream.range(0, chainLength)
                    .boxed()
                    .collect(Collectors.toList());
            assertThat(orderedDepths).isEqualTo(expectedDepths);
        }

        private ChainLink buildChain(int length) {
            ChainLink current = null;
            for (int i = length - 1; i >= 0; i--) {
                current = proxy(new ChainLinkImpl(current));
            }
            return current;
        }
    }

    @Nested
    @DisplayName("robustness against misbehaving observability collaborators")
    class Robustness {

        @Test
        @DisplayName("propagates the method's own exception even when MetricsRecorder itself throws")
        void propagatesOriginalExceptionWhenMetricsRecorderThrows() {
            LoggedAspect faultyAspect = new LoggedAspect(
                    eventEmitter,
                    (className, methodName, durationNanos, success, exceptionType) -> {
                        throw new IllegalStateException("metrics backend unreachable");
                    },
                    () -> CALLER_IDENTITY
            );
            AspectJProxyFactory factory = new AspectJProxyFactory(new CalculatorImpl());
            factory.addAspect(faultyAspect);
            Calculator calculator = factory.getProxy();

            assertThatThrownBy(() -> calculator.divide(10, 0))
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        @DisplayName("propagates the method's own exception even when InvocationEventEmitter itself throws")
        void propagatesOriginalExceptionWhenEventEmitterThrows() {
            InvocationEventEmitter faultyEmitter = event -> {
                throw new IllegalStateException("log sink unreachable");
            };
            LoggedAspect faultyAspect = new LoggedAspect(faultyEmitter, metricsRecorder, () -> CALLER_IDENTITY);
            AspectJProxyFactory factory = new AspectJProxyFactory(new CalculatorImpl());
            factory.addAspect(faultyAspect);
            Calculator calculator = factory.getProxy();

            assertThatThrownBy(() -> calculator.divide(10, 0))
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        @DisplayName("still returns the method's own successful result even when MetricsRecorder throws")
        void returnsOriginalResultWhenMetricsRecorderThrows() {
            LoggedAspect faultyAspect = new LoggedAspect(
                    eventEmitter,
                    (className, methodName, durationNanos, success, exceptionType) -> {
                        throw new IllegalStateException("metrics backend unreachable");
                    },
                    () -> CALLER_IDENTITY
            );
            AspectJProxyFactory factory = new AspectJProxyFactory(new CalculatorImpl());
            factory.addAspect(faultyAspect);
            Calculator calculator = factory.getProxy();

            assertThat(calculator.add(2, 3)).isEqualTo(5);
        }
    }

    interface ChainLink {
        void run();
    }

    static class ChainLinkImpl implements ChainLink {
        private final ChainLink next;

        ChainLinkImpl(ChainLink next) {
            this.next = next;
        }

        @Logged
        @Override
        public void run() {
            if (next != null) {
                next.run();
            }
        }
    }

    interface Calculator {
        int add(int a, int b);

        int divide(int a, int b);
    }

    static class CalculatorImpl implements Calculator {
        @Logged(slowThresholdMs = 1000, sampleRate = 1.0)
        @Override
        public int add(int a, int b) {
            return a + b;
        }

        @Logged(slowThresholdMs = 1000, sampleRate = 1.0)
        @Override
        public int divide(int a, int b) {
            return a / b;
        }
    }

    static class SlowCalculatorImpl implements Calculator {
        @Logged(slowThresholdMs = 0, sampleRate = 0.0)
        @Override
        public int add(int a, int b) {
            return a + b;
        }

        @Override
        public int divide(int a, int b) {
            return a / b;
        }
    }

    static class UnsampledCalculatorImpl implements Calculator {
        @Logged(slowThresholdMs = 1000, sampleRate = 0.0)
        @Override
        public int add(int a, int b) {
            return a + b;
        }

        @Logged(slowThresholdMs = 1000, sampleRate = 0.0)
        @Override
        public int divide(int a, int b) {
            return a / b;
        }
    }

    interface ServiceA {
        void process();
    }

    interface ServiceB {
        void doWork();
    }

    static class ServiceAImpl implements ServiceA {
        private final ServiceB serviceB;

        ServiceAImpl(ServiceB serviceB) {
            this.serviceB = serviceB;
        }

        @Logged
        @Override
        public void process() {
            serviceB.doWork();
        }
    }

    static class ServiceBImpl implements ServiceB {
        @Logged
        @Override
        public void doWork() {
            // no-op: only the call itself matters for this test
        }
    }

    static class RecordingEventEmitter implements InvocationEventEmitter {
        private final List<MethodInvocationEvent> events = new ArrayList<>();

        @Override
        public void emit(MethodInvocationEvent event) {
            events.add(event);
            System.out.println("EMITTED -> " + event);
        }

        void printAll() {
            System.out.println("Total events captured: " + events.size());
        }
    }

    static class RecordingMetricsRecorder implements MetricsRecorder {
        private final List<MethodInvocationEvent> recordings = new ArrayList<>();

        @Override
        public void record(String className, String methodName, long durationNanos, boolean success, String exceptionType) {
            MethodInvocationEvent recorded = new MethodInvocationEvent(
                    className, methodName, java.time.Instant.now(), durationNanos, success, exceptionType, "n/a", "n/a", -1
            );
            recordings.add(recorded);
            System.out.println("METRIC -> " + recorded);
        }

        void printAll() {
            System.out.println("Total metrics recorded: " + recordings.size());
        }
    }
}
