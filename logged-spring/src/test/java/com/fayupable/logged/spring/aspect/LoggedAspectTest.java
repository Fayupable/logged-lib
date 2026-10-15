package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.annotation.CallerMode;
import com.fayupable.logged.core.annotation.Logged;
import com.fayupable.logged.core.model.MethodInvocationEvent;
import com.fayupable.logged.core.port.IClientInfoPort;
import com.fayupable.logged.core.port.InvocationEventEmitter;
import com.fayupable.logged.core.port.MetricsRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private <T> T proxy(T target) {
        return proxyWith(aspect, target);
    }

    private <T> T proxyWith(LoggedAspect aspectInstance, T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspectInstance);
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
        @DisplayName("measures a plausible duration, not the sum of a nanoTime reading with itself")
        void measuresAPlausibleDuration() {
            Calculator calculator = proxy(new CalculatorImpl());

            calculator.add(2, 3);

            long durationNanos = eventEmitter.events.get(0).durationNanos();
            assertThat(durationNanos).isPositive()
                    .isLessThan(TimeUnit.SECONDS.toNanos(5));
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
        @DisplayName("measures a plausible duration, not the sum of a nanoTime reading with itself")
        void measuresAPlausibleDuration() {
            Calculator calculator = proxy(new CalculatorImpl());

            try {
                calculator.divide(10, 0);
            } catch (ArithmeticException ignored) {
                // expected
            }

            long durationNanos = eventEmitter.events.get(0).durationNanos();
            assertThat(durationNanos).isPositive()
                    .isLessThan(TimeUnit.SECONDS.toNanos(5));
        }

        @Test
        @DisplayName("closes the flow scope on the calling thread even when the intercepted method throws")
        void closesFlowScopeWhenMethodThrows() {
            Calculator calculator = proxy(new CalculatorImpl());

            try {
                calculator.divide(10, 0);
            } catch (ArithmeticException ignored) {
                // expected
            }

            assertThat(FlowContextHolder.snapshot()).isNull();
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
        @DisplayName("reports the same root cause type as exception type when the exception has no cause")
        void rootCauseEqualsExceptionTypeWhenNoCause() {
            Calculator calculator = proxy(new CalculatorImpl());

            try {
                calculator.divide(10, 0);
            } catch (ArithmeticException ignored) {
                // expected
            }

            MethodInvocationEvent event = eventEmitter.events.get(0);

            assertThat(event.rootCauseType()).isEqualTo(event.exceptionType());
        }

        @Test
        @DisplayName("reports the deepest cause's type when the thrown exception wraps another exception")
        void reportsDeepestCauseType() {
            Calculator calculator = proxy(new WrappingCalculatorImpl());

            try {
                calculator.divide(10, 0);
            } catch (IllegalStateException ignored) {
                // expected
            }

            MethodInvocationEvent event = eventEmitter.events.get(0);

            assertThat(event.exceptionType()).isEqualTo("IllegalStateException");
            assertThat(event.rootCauseType()).isEqualTo("ArithmeticException");
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
    @DisplayName("on a method returning CompletableFuture")
    class OnCompletableFuture {

        @Test
        @DisplayName("does not emit until the returned future actually completes")
        void doesNotEmitBeforeCompletion() {
            AsyncService service = proxy(new AsyncServiceImpl());
            CompletableFuture<Integer> manuallyCompleted = new CompletableFuture<>();

            service.manualAsync(manuallyCompleted);

            assertThat(eventEmitter.events).isEmpty();

            manuallyCompleted.complete(99);

            assertThat(eventEmitter.events).hasSize(1);
        }

        @Test
        @DisplayName("closes the flow scope and clears the MDC synchronously, before the future even completes")
        void closesFlowScopeAndMdcSynchronously() {
            AsyncService service = proxy(new AsyncServiceImpl());
            CompletableFuture<Integer> manuallyCompleted = new CompletableFuture<>();

            service.manualAsync(manuallyCompleted);

            assertThat(FlowContextHolder.snapshot())
                    .as("flow scope must already be closed on the calling thread, before the future completes")
                    .isNull();
            assertThat(MDC.get(LoggedMdcKeys.TRACE_ID))
                    .as("MDC must already be cleared on the calling thread, before the future completes")
                    .isNull();

            manuallyCompleted.complete(99);
        }

        @Test
        @DisplayName("emits success with a duration reflecting the real asynchronous work, not just submission")
        void emitsSuccessAfterRealCompletion() throws Exception {
            AsyncService service = proxy(new AsyncServiceImpl());

            Integer value = service.computeAsync(21).get(5, TimeUnit.SECONDS);

            assertThat(value).isEqualTo(42);
            assertThat(eventEmitter.events).hasSize(1);
            MethodInvocationEvent event = eventEmitter.events.get(0);
            System.out.println("ASYNC EVENT -> " + event);
            assertThat(event.success()).isTrue();
            assertThat(event.durationNanos()).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(15));
        }

        @Test
        @DisplayName("still returns the same value to the caller as an unadvised call would")
        void returnsSameValueAsCaller() throws Exception {
            AsyncService service = proxy(new AsyncServiceImpl());

            Integer value = service.computeAsync(10).get(5, TimeUnit.SECONDS);

            assertThat(value).isEqualTo(20);
        }

        @Test
        @DisplayName("records a failure thrown directly by the async work")
        void recordsDirectFailure() {
            AsyncService service = proxy(new AsyncServiceImpl());

            awaitCompletion(service.failDirectlyAsync());

            assertThat(eventEmitter.events).hasSize(1);
            MethodInvocationEvent event = eventEmitter.events.get(0);
            System.out.println("ASYNC FAILURE EVENT -> " + event);
            assertThat(event.success()).isFalse();
            assertThat(event.exceptionType()).isEqualTo("ArithmeticException");
            assertThat(event.rootCauseType()).isEqualTo("ArithmeticException");
            assertThat(event.durationNanos()).isPositive()
                    .isLessThan(TimeUnit.SECONDS.toNanos(5));
        }

        @Test
        @DisplayName("unwraps CompletionException to report the actual exception thrown by a chained stage")
        void unwrapsCompletionExceptionFromChainedStage() {
            AsyncService service = proxy(new AsyncServiceImpl());

            awaitCompletion(service.failWrappedAsync());

            assertThat(eventEmitter.events).hasSize(1);
            MethodInvocationEvent event = eventEmitter.events.get(0);
            System.out.println("ASYNC WRAPPED FAILURE EVENT -> " + event);
            assertThat(event.exceptionType()).isEqualTo("IllegalStateException");
            assertThat(event.rootCauseType()).isEqualTo("IllegalStateException");
            assertThat(event.exceptionType()).isNotEqualTo("CompletionException");
        }

        @Test
        @DisplayName("still propagates the original failure to a caller waiting on the future")
        void stillPropagatesFailureToCaller() {
            AsyncService service = proxy(new AsyncServiceImpl());
            CompletableFuture<Integer> future = service.failDirectlyAsync();

            assertThatThrownBy(future::join)
                    .hasRootCauseInstanceOf(ArithmeticException.class);
        }

        private void awaitCompletion(CompletableFuture<?> future) {
            try {
                future.join();
            } catch (CompletionException ignored) {
                // expected: we only need the future to have completed before asserting
            }
        }
    }

    @Nested
    @DisplayName("MDC propagation")
    class MdcPropagationBehavior {

        @Test
        @DisplayName("writes trace id, depth, class name, and method name for the duration of the call")
        void writesMdcKeysDuringCall() {
            MdcAwareServiceImpl service = new MdcAwareServiceImpl();
            MdcAwareService proxied = proxy(service);

            proxied.doWork();

            MethodInvocationEvent event = eventEmitter.events.get(0);
            assertThat(service.capturedTraceId.get()).isEqualTo(event.traceId());
            assertThat(service.capturedDepth.get()).isEqualTo(String.valueOf(event.depth()));
            assertThat(service.capturedClassName.get()).isEqualTo(event.className());
            assertThat(service.capturedMethodName.get()).isEqualTo(event.methodName());
        }

        @Test
        @DisplayName("clears the MDC once a root-level call returns")
        void clearsMdcAfterRootCallReturns() {
            MdcAwareService proxied = proxy(new MdcAwareServiceImpl());

            proxied.doWork();

            assertThat(MDC.get(LoggedMdcKeys.TRACE_ID)).isNull();
            assertThat(MDC.get(LoggedMdcKeys.CLASS_NAME)).isNull();
        }

        @Test
        @DisplayName("restores the outer call's MDC values once a nested call returns")
        void restoresOuterMdcValuesAfterNestedCallReturns() {
            MdcNestedInnerImpl inner = new MdcNestedInnerImpl();
            MdcNestedOuterImpl outer = new MdcNestedOuterImpl(proxy(inner));
            MdcNestedOuter proxiedOuter = proxy(outer);

            proxiedOuter.outer();

            assertThat(outer.depthDuringOuter.get()).isEqualTo("0");
            assertThat(inner.depthDuringInner.get()).isEqualTo("1");
            assertThat(outer.depthAfterInnerReturns.get()).isEqualTo("0");
        }

        @Test
        @DisplayName("still clears the MDC when the intercepted method throws")
        void clearsMdcWhenMethodThrows() {
            Calculator calculator = proxy(new CalculatorImpl());

            try {
                calculator.divide(10, 0);
            } catch (ArithmeticException ignored) {
                // expected
            }

            assertThat(MDC.get(LoggedMdcKeys.TRACE_ID)).isNull();
            assertThat(MDC.get(LoggedMdcKeys.CLASS_NAME)).isNull();
        }

        @Test
        @DisplayName("does not touch the MDC at all when disabled")
        void doesNotTouchMdcWhenDisabled() {
            LoggedAspect mdcDisabledAspect = new LoggedAspect(eventEmitter, metricsRecorder, () -> CALLER_IDENTITY, false);
            AspectJProxyFactory factory = new AspectJProxyFactory(new MdcAwareServiceImpl());
            factory.addAspect(mdcDisabledAspect);
            MdcAwareService proxied = factory.getProxy();

            proxied.doWork();

            assertThat(MDC.getCopyOfContextMap()).isNull();
        }
    }

    @Nested
    @DisplayName("Logged#includeIp")
    class IncludeIp {

        private static final String CALLER_IP = "203.0.113.5";

        @Test
        @DisplayName("populates callerIp on a successful call when includeIp = true")
        void populatesCallerIpOnSuccessWhenEnabled() {
            CountingClientInfoPort clientInfoPort = new CountingClientInfoPort(CALLER_IDENTITY, CALLER_IP);
            LoggedAspect ipAwareAspect = new LoggedAspect(eventEmitter, metricsRecorder, clientInfoPort);
            SecuritySensitiveService service = proxyWith(ipAwareAspect, new SecuritySensitiveServiceImpl());

            service.login("enis");

            assertThat(eventEmitter.events.get(0).callerIp()).isEqualTo(CALLER_IP);
            assertThat(clientInfoPort.resolveCallerIpInvocations).isEqualTo(1);
        }

        @Test
        @DisplayName("leaves callerIp null when includeIp = false (the default), without calling resolveCallerIp")
        void leavesCallerIpNullWhenDisabled() {
            CountingClientInfoPort clientInfoPort = new CountingClientInfoPort(CALLER_IDENTITY, CALLER_IP);
            LoggedAspect ipAwareAspect = new LoggedAspect(eventEmitter, metricsRecorder, clientInfoPort);
            SecuritySensitiveService service = proxyWith(ipAwareAspect, new SecuritySensitiveServiceImpl());

            service.readData();

            assertThat(eventEmitter.events.get(0).callerIp()).isNull();
            assertThat(clientInfoPort.resolveCallerIpInvocations).isZero();
        }

        @Test
        @DisplayName("populates callerIp on a failed call when includeIp = true")
        void populatesCallerIpOnFailureWhenEnabled() {
            CountingClientInfoPort clientInfoPort = new CountingClientInfoPort(CALLER_IDENTITY, CALLER_IP);
            LoggedAspect ipAwareAspect = new LoggedAspect(eventEmitter, metricsRecorder, clientInfoPort);
            SecuritySensitiveService service = proxyWith(ipAwareAspect, new SecuritySensitiveServiceImpl());

            assertThatThrownBy(() -> service.login("invalid")).isInstanceOf(IllegalArgumentException.class);

            assertThat(eventEmitter.events.get(0).success()).isFalse();
            assertThat(eventEmitter.events.get(0).callerIp()).isEqualTo(CALLER_IP);
        }

        @Test
        @DisplayName("populates callerIp once a CompletableFuture-returning call actually completes")
        void populatesCallerIpAfterFutureCompletion() {
            CountingClientInfoPort clientInfoPort = new CountingClientInfoPort(CALLER_IDENTITY, CALLER_IP);
            LoggedAspect ipAwareAspect = new LoggedAspect(eventEmitter, metricsRecorder, clientInfoPort);
            SecuritySensitiveService service = proxyWith(ipAwareAspect, new SecuritySensitiveServiceImpl());
            CompletableFuture<String> manuallyCompleted = new CompletableFuture<>();

            service.loginAsync(manuallyCompleted);
            assertThat(eventEmitter.events).isEmpty();

            manuallyCompleted.complete("ok");

            assertThat(eventEmitter.events.get(0).callerIp()).isEqualTo(CALLER_IP);
        }
    }

    @Nested
    @DisplayName("Logged#includeDevice")
    class IncludeDevice {

        private static final String DEVICE = "Windows / Chrome 129";

        private CountingClientInfoPort clientInfoPort;

        @BeforeEach
        void setUpPort() {
            clientInfoPort = new CountingClientInfoPort(CALLER_IDENTITY, null, DEVICE);
        }

        private SecuritySensitiveService serviceWith(boolean deviceIncludedGlobally) {
            LoggedAspect deviceAspect = new LoggedAspect(eventEmitter, metricsRecorder, clientInfoPort, true,
                    deviceIncludedGlobally);
            return proxyWith(deviceAspect, new SecuritySensitiveServiceImpl());
        }

        @Test
        @DisplayName("populates callerDevice when includeDevice = true")
        void populatesCallerDeviceWhenAnnotated() {
            serviceWith(false).register();

            assertThat(eventEmitter.events.get(0).callerDevice()).isEqualTo(DEVICE);
            assertThat(clientInfoPort.resolveCallerDeviceInvocations).isEqualTo(1);
        }

        @Test
        @DisplayName("leaves callerDevice null by default, without calling resolveCallerDevice")
        void leavesCallerDeviceNullByDefault() {
            serviceWith(false).readData();

            assertThat(eventEmitter.events.get(0).callerDevice()).isNull();
            assertThat(clientInfoPort.resolveCallerDeviceInvocations).isZero();
        }

        @Test
        @DisplayName("populates callerDevice for every method when enabled globally")
        void populatesCallerDeviceWhenEnabledGlobally() {
            serviceWith(true).readData();

            assertThat(eventEmitter.events.get(0).callerDevice()).isEqualTo(DEVICE);
        }

        @Test
        @DisplayName("keeps the legacy constructors' default of no device info")
        void legacyConstructorsLeaveDeviceOff() {
            LoggedAspect legacyAspect = new LoggedAspect(eventEmitter, metricsRecorder, clientInfoPort, true);
            SecuritySensitiveService service = proxyWith(legacyAspect, new SecuritySensitiveServiceImpl());
            service.readData();

            assertThat(eventEmitter.events.get(0).callerDevice()).isNull();
            assertThat(clientInfoPort.resolveCallerDeviceInvocations).isZero();
        }

        @Test
        @DisplayName("exposes the device in the MDC during the call and removes it afterwards")
        void exposesDeviceInMdc() {
            String seenInsideCall = serviceWith(false).register();

            assertThat(seenInsideCall).isEqualTo(DEVICE);
            assertThat(MDC.get(LoggedMdcKeys.CALLER_DEVICE)).isNull();
        }

        @Test
        @DisplayName("captures the device before a CompletableFuture-returning call completes")
        void capturesDeviceForCompletableFuture() {
            CompletableFuture<String> future = new CompletableFuture<>();
            serviceWith(false).registerAsync(future);
            assertThat(eventEmitter.events).isEmpty();

            future.complete("ok");

            assertThat(eventEmitter.events.get(0).callerDevice()).isEqualTo(DEVICE);
            assertThat(clientInfoPort.resolveCallerDeviceInvocations).isEqualTo(1);
        }

        @Test
        @DisplayName("leaves callerDevice null when the port does not support device resolution")
        void leavesCallerDeviceNullForLegacyPort() {
            LoggedAspect legacyPortAspect = new LoggedAspect(eventEmitter, metricsRecorder, () -> CALLER_IDENTITY, true, true);
            SecuritySensitiveService service = proxyWith(legacyPortAspect, new SecuritySensitiveServiceImpl());
            service.register();

            assertThat(eventEmitter.events.get(0).callerDevice()).isNull();
        }
    }

    @Nested
    @DisplayName("Logged#caller")
    class CallerModeOverride {

        @Test
        @DisplayName("passes the method's caller mode to the client info port")
        void passesMethodCallerMode() {
            List<CallerMode> requested = new ArrayList<>();
            IClientInfoPort modeAwarePort = new IClientInfoPort() {
                @Override
                public String resolveCallerIdentity() {
                    return CALLER_IDENTITY;
                }

                @Override
                public String resolveCallerIdentity(CallerMode mode) {
                    requested.add(mode);
                    return "user:" + mode.name();
                }
            };
            LoggedAspect modeAwareAspect = new LoggedAspect(eventEmitter, metricsRecorder, modeAwarePort);
            SecuritySensitiveService service = proxyWith(modeAwareAspect, new SecuritySensitiveServiceImpl());

            service.readData();
            service.audit();

            assertThat(requested).containsExactly(CallerMode.DEFAULT, CallerMode.NAME);
            assertThat(eventEmitter.events).extracting(MethodInvocationEvent::callerIdentity)
                    .containsExactly("user:DEFAULT", "user:NAME");
        }

        @Test
        @DisplayName("keeps working with a port that only implements resolveCallerIdentity()")
        void worksWithLegacyPort() {
            SecuritySensitiveService service = proxy(new SecuritySensitiveServiceImpl());

            service.audit();

            assertThat(eventEmitter.events.get(0).callerIdentity()).isEqualTo(CALLER_IDENTITY);
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

        @Test
        @DisplayName("still restores the flow scope and MDC on this thread even when a collaborator throws an Error")
        void restoresFlowScopeAndMdcWhenCollaboratorThrowsError() {
            LoggedAspect faultyAspect = new LoggedAspect(
                    eventEmitter,
                    (className, methodName, durationNanos, success, exceptionType) -> {
                        throw new SimulatedObservabilityError("metrics backend crashed unrecoverably");
                    },
                    () -> CALLER_IDENTITY
            );
            AspectJProxyFactory factory = new AspectJProxyFactory(new CalculatorImpl());
            factory.addAspect(faultyAspect);
            Calculator calculator = factory.getProxy();

            assertThatThrownBy(() -> calculator.add(2, 3))
                    .isInstanceOf(SimulatedObservabilityError.class);

            assertThat(FlowContextHolder.snapshot()).isNull();
            assertThat(MDC.get(LoggedMdcKeys.TRACE_ID)).isNull();
        }

        /**
         * Deliberately an {@link Error}, not a {@link RuntimeException}:
         * {@code LoggedAspect} only catches {@code RuntimeException} inside
         * {@code recordObservability}, by design, so this simulates the one
         * category of collaborator failure that is expected to actually
         * propagate past it.
         */
        private static class SimulatedObservabilityError extends Error {
            SimulatedObservabilityError(String message) {
                super(message);
            }
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

    static class WrappingCalculatorImpl implements Calculator {
        @Logged(slowThresholdMs = 1000, sampleRate = 1.0)
        @Override
        public int add(int a, int b) {
            return a + b;
        }

        @Logged(slowThresholdMs = 1000, sampleRate = 1.0)
        @Override
        public int divide(int a, int b) {
            try {
                return a / b;
            } catch (ArithmeticException cause) {
                throw new IllegalStateException("calculation failed", cause);
            }
        }
    }

    interface SecuritySensitiveService {
        String login(String username);

        String readData();

        CompletableFuture<String> loginAsync(CompletableFuture<String> future);

        String audit();

        String register();

        CompletableFuture<String> registerAsync(CompletableFuture<String> future);
    }

    static class SecuritySensitiveServiceImpl implements SecuritySensitiveService {
        @Logged(includeIp = true)
        @Override
        public String login(String username) {
            if ("invalid".equals(username)) {
                throw new IllegalArgumentException("bad credentials");
            }
            return "ok:" + username;
        }

        @Logged
        @Override
        public String readData() {
            return "data";
        }

        @Logged(includeIp = true)
        @Override
        public CompletableFuture<String> loginAsync(CompletableFuture<String> future) {
            return future;
        }

        @Logged(caller = CallerMode.NAME)
        @Override
        public String audit() {
            return "audited";
        }

        @Logged(includeDevice = true)
        @Override
        public String register() {
            return MDC.get(LoggedMdcKeys.CALLER_DEVICE);
        }

        @Logged(includeDevice = true)
        @Override
        public CompletableFuture<String> registerAsync(CompletableFuture<String> future) {
            return future;
        }
    }

    static class CountingClientInfoPort implements IClientInfoPort {
        private final String identity;
        private final String ip;
        private final String device;
        int resolveCallerIpInvocations;
        int resolveCallerDeviceInvocations;

        CountingClientInfoPort(String identity, String ip) {
            this(identity, ip, null);
        }

        CountingClientInfoPort(String identity, String ip, String device) {
            this.identity = identity;
            this.ip = ip;
            this.device = device;
        }

        @Override
        public String resolveCallerDevice() {
            resolveCallerDeviceInvocations++;
            return device;
        }

        @Override
        public String resolveCallerIdentity() {
            return identity;
        }

        @Override
        public String resolveCallerIp() {
            resolveCallerIpInvocations++;
            return ip;
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

    interface AsyncService {
        CompletableFuture<Integer> computeAsync(int value);

        CompletableFuture<Integer> failDirectlyAsync();

        CompletableFuture<Integer> failWrappedAsync();

        CompletableFuture<Integer> manualAsync(CompletableFuture<Integer> future);
    }

    static class AsyncServiceImpl implements AsyncService {
        @Logged(slowThresholdMs = 1000, sampleRate = 1.0)
        @Override
        public CompletableFuture<Integer> computeAsync(int value) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return value * 2;
            });
        }

        @Logged(slowThresholdMs = 1000, sampleRate = 1.0)
        @Override
        public CompletableFuture<Integer> failDirectlyAsync() {
            return CompletableFuture.supplyAsync(() -> {
                throw new ArithmeticException("boom");
            });
        }

        @Logged(slowThresholdMs = 1000, sampleRate = 1.0)
        @Override
        public CompletableFuture<Integer> failWrappedAsync() {
            return CompletableFuture.supplyAsync(() -> 10)
                    .thenApply(v -> {
                        throw new IllegalStateException("boom");
                    });
        }

        @Logged(slowThresholdMs = 1000, sampleRate = 1.0)
        @Override
        public CompletableFuture<Integer> manualAsync(CompletableFuture<Integer> future) {
            return future;
        }
    }

    interface MdcAwareService {
        void doWork();
    }

    static class MdcAwareServiceImpl implements MdcAwareService {
        final AtomicReference<String> capturedTraceId = new AtomicReference<>();
        final AtomicReference<String> capturedDepth = new AtomicReference<>();
        final AtomicReference<String> capturedClassName = new AtomicReference<>();
        final AtomicReference<String> capturedMethodName = new AtomicReference<>();

        @Logged(slowThresholdMs = 1000, sampleRate = 1.0)
        @Override
        public void doWork() {
            capturedTraceId.set(MDC.get(LoggedMdcKeys.TRACE_ID));
            capturedDepth.set(MDC.get(LoggedMdcKeys.DEPTH));
            capturedClassName.set(MDC.get(LoggedMdcKeys.CLASS_NAME));
            capturedMethodName.set(MDC.get(LoggedMdcKeys.METHOD_NAME));
        }
    }

    interface MdcNestedOuter {
        void outer();
    }

    interface MdcNestedInner {
        void inner();
    }

    static class MdcNestedOuterImpl implements MdcNestedOuter {
        private final MdcNestedInner inner;
        final AtomicReference<String> depthDuringOuter = new AtomicReference<>();
        final AtomicReference<String> depthAfterInnerReturns = new AtomicReference<>();

        MdcNestedOuterImpl(MdcNestedInner inner) {
            this.inner = inner;
        }

        @Logged(slowThresholdMs = 1000, sampleRate = 1.0)
        @Override
        public void outer() {
            depthDuringOuter.set(MDC.get(LoggedMdcKeys.DEPTH));
            inner.inner();
            depthAfterInnerReturns.set(MDC.get(LoggedMdcKeys.DEPTH));
        }
    }

    static class MdcNestedInnerImpl implements MdcNestedInner {
        final AtomicReference<String> depthDuringInner = new AtomicReference<>();

        @Logged(slowThresholdMs = 1000, sampleRate = 1.0)
        @Override
        public void inner() {
            depthDuringInner.set(MDC.get(LoggedMdcKeys.DEPTH));
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
                    className, methodName, java.time.Instant.now(), durationNanos, success, exceptionType, exceptionType, "n/a", null, "n/a", -1
            );
            recordings.add(recorded);
            System.out.println("METRIC -> " + recorded);
        }

        void printAll() {
            System.out.println("Total metrics recorded: " + recordings.size());
        }
    }
}
