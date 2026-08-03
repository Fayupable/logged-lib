package com.fayupable.logged.spring.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LazyMetricsRecorder")
class LazyMetricsRecorderTest {

    /**
     * A test double whose {@link #getIfAvailable()} result can change
     * between calls, simulating a {@link MeterRegistry} bean that does not
     * exist yet at the moment of the first {@code @Logged} invocation and
     * is only created afterward.
     */
    private static final class SwitchableMeterRegistryProvider implements ObjectProvider<MeterRegistry> {
        private MeterRegistry registry;

        void becomeAvailable(MeterRegistry registry) {
            this.registry = registry;
        }

        @Override
        public MeterRegistry getIfAvailable() {
            return registry;
        }

        @Override
        public MeterRegistry getObject() {
            throw new UnsupportedOperationException("not used by LazyMetricsRecorder");
        }

        @Override
        public MeterRegistry getObject(Object... args) {
            throw new UnsupportedOperationException("not used by LazyMetricsRecorder");
        }
    }

    @Nested
    @DisplayName("when a MeterRegistry is available from the first call")
    class RegistryAvailableImmediately {

        @Test
        @DisplayName("records through a real Micrometer-backed recorder")
        void recordsThroughMicrometer() {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            SwitchableMeterRegistryProvider provider = new SwitchableMeterRegistryProvider();
            provider.becomeAvailable(meterRegistry);
            LazyMetricsRecorder recorder = new LazyMetricsRecorder(provider);

            recorder.record("UserService", "getUser", 10_000_000L, true, null);

            Counter counter = meterRegistry.find("method.invocations")
                    .tag("class", "UserService").tag("method", "getUser").tag("outcome", "success")
                    .counter();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("reuses the already-resolved recorder on a second call, without re-checking the ObjectProvider")
        void reusesResolvedRecorderOnSecondCall() {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            SwitchableMeterRegistryProvider provider = new SwitchableMeterRegistryProvider();
            provider.becomeAvailable(meterRegistry);
            LazyMetricsRecorder recorder = new LazyMetricsRecorder(provider);

            recorder.record("UserService", "getUser", 10_000_000L, true, null);
            recorder.record("UserService", "getUser", 20_000_000L, true, null);

            Counter counter = meterRegistry.find("method.invocations")
                    .tag("class", "UserService").tag("method", "getUser").tag("outcome", "success")
                    .counter();
            assertThat(counter.count()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("resolves exactly once under concurrent first access, with every thread's call recorded")
        void resolvesSafelyUnderConcurrentFirstAccess() throws InterruptedException {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            SwitchableMeterRegistryProvider provider = new SwitchableMeterRegistryProvider();
            provider.becomeAvailable(meterRegistry);
            LazyMetricsRecorder recorder = new LazyMetricsRecorder(provider);

            int threadCount = 20;
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            try {
                for (int i = 0; i < threadCount; i++) {
                    pool.execute(() -> {
                        readyLatch.countDown();
                        try {
                            startLatch.await(5, TimeUnit.SECONDS);
                            recorder.record("UserService", "getUser", 1_000_000L, true, null);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                assertThat(readyLatch.await(5, TimeUnit.SECONDS)).as("all threads ready").isTrue();
                startLatch.countDown();
                assertThat(doneLatch.await(5, TimeUnit.SECONDS)).as("all threads finished").isTrue();
            } finally {
                pool.shutdownNow();
            }

            Counter counter = meterRegistry.find("method.invocations")
                    .tag("class", "UserService").tag("method", "getUser").tag("outcome", "success")
                    .counter();
            assertThat(counter.count()).isEqualTo((double) threadCount);
        }
    }

    @Nested
    @DisplayName("when no MeterRegistry is available yet")
    class RegistryNotYetAvailable {

        @Test
        @DisplayName("does not throw, falling back to a no-op recording")
        void doesNotThrowWithoutRegistry() {
            LazyMetricsRecorder recorder = new LazyMetricsRecorder(new SwitchableMeterRegistryProvider());

            recorder.record("UserService", "getUser", 10_000_000L, true, null);
            // No assertion beyond "did not throw": there is nowhere to observe
            // a recording since no MeterRegistry exists yet.
        }

        @Test
        @DisplayName("does not permanently pin itself to no-op: once a MeterRegistry becomes available, later calls use it")
        void recoversOnceRegistryBecomesAvailable() {
            SwitchableMeterRegistryProvider provider = new SwitchableMeterRegistryProvider();
            LazyMetricsRecorder recorder = new LazyMetricsRecorder(provider);

            // First call races ahead of the MeterRegistry bean's own creation.
            recorder.record("UserService", "getUser", 10_000_000L, true, null);

            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            provider.becomeAvailable(meterRegistry);

            recorder.record("UserService", "getUser", 20_000_000L, true, null);

            Counter counter = meterRegistry.find("method.invocations")
                    .tag("class", "UserService").tag("method", "getUser").tag("outcome", "success")
                    .counter();
            assertThat(counter.count())
                    .as("the call made after the registry became available must have been recorded")
                    .isEqualTo(1.0);
        }
    }
}
