package com.fayupable.logged.spring.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MicrometerMetricsRecorder")
class MicrometerMetricsRecorderTest {

    private SimpleMeterRegistry meterRegistry;
    private MicrometerMetricsRecorder recorder;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        recorder = new MicrometerMetricsRecorder(meterRegistry);
    }

    @Nested
    @DisplayName("on a successful invocation")
    class SuccessfulInvocation {

        @Test
        @DisplayName("increments the invocation counter with outcome = success")
        void incrementsInvocationCounter() {
            recorder.record("UserService", "getUser", 10_000_000L, true, null);

            Counter counter = meterRegistry.find("method.invocations")
                    .tag("class", "UserService")
                    .tag("method", "getUser")
                    .tag("outcome", "success")
                    .counter();

            System.out.println("invocation counter count = " + counter.count());

            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("records the duration timer")
        void recordsDurationTimer() {
            recorder.record("UserService", "getUser", 10_000_000L, true, null);

            Timer timer = meterRegistry.find("method.duration")
                    .tag("class", "UserService")
                    .tag("method", "getUser")
                    .timer();

            System.out.println("timer count = " + timer.count() + ", totalTime(ms) = " + timer.totalTime(TimeUnit.MILLISECONDS));

            assertThat(timer.count()).isEqualTo(1L);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(10.0);
        }

        @Test
        @DisplayName("does not register an error counter")
        void doesNotRegisterErrorCounter() {
            recorder.record("UserService", "getUser", 10_000_000L, true, null);

            Counter errorCounter = meterRegistry.find("method.errors").counter();

            assertThat(errorCounter).isNull();
        }
    }

    @Nested
    @DisplayName("on a failed invocation")
    class FailedInvocation {

        @Test
        @DisplayName("increments the invocation counter with outcome = error")
        void incrementsInvocationCounterWithErrorOutcome() {
            recorder.record("UserService", "getUser", 5_000_000L, false, "IllegalArgumentException");

            Counter counter = meterRegistry.find("method.invocations")
                    .tag("class", "UserService")
                    .tag("method", "getUser")
                    .tag("outcome", "error")
                    .counter();

            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("increments the error counter tagged with the exception type")
        void incrementsErrorCounter() {
            recorder.record("UserService", "getUser", 5_000_000L, false, "IllegalArgumentException");

            Counter errorCounter = meterRegistry.find("method.errors")
                    .tag("class", "UserService")
                    .tag("method", "getUser")
                    .tag("exception", "IllegalArgumentException")
                    .counter();

            System.out.println("error counter count = " + errorCounter.count());

            assertThat(errorCounter.count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("across multiple invocations")
    class MultipleInvocations {

        @Test
        @DisplayName("accumulates the same meters instead of creating duplicates")
        void accumulatesSameMeters() {
            recorder.record("UserService", "getUser", 10_000_000L, true, null);
            recorder.record("UserService", "getUser", 20_000_000L, true, null);
            recorder.record("UserService", "getUser", 30_000_000L, false, "IllegalStateException");

            Counter invocationCounter = meterRegistry.find("method.invocations")
                    .tag("class", "UserService")
                    .tag("method", "getUser")
                    .tag("outcome", "success")
                    .counter();
            Timer timer = meterRegistry.find("method.duration")
                    .tag("class", "UserService")
                    .tag("method", "getUser")
                    .timer();

            System.out.println("success invocations = " + invocationCounter.count());
            System.out.println("timer count (all outcomes) = " + timer.count());

            assertThat(invocationCounter.count()).isEqualTo(2.0);
            assertThat(timer.count()).isEqualTo(3L);
        }
    }
}
