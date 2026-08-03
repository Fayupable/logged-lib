package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.model.FlowContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FlowContextPropagatingExecutor")
class FlowContextPropagatingExecutorTest {

    private ExecutorService delegate;

    @AfterEach
    void tearDown() {
        if (delegate != null) {
            delegate.shutdownNow();
        }
        MDC.clear();
    }

    /**
     * Runs {@code command} on {@link #delegate} and blocks until it
     * finishes, so assertions on the calling thread can rely on the
     * submitted task having already run.
     */
    private void executeAndAwait(FlowContextPropagatingExecutor executor, Runnable command) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                command.run();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).as("submitted task completed in time").isTrue();
    }

    @Nested
    @DisplayName("on a platform thread pool")
    class OnPlatformThreadPool {

        @Test
        @DisplayName("propagates the submitting thread's context onto the pool thread")
        void propagatesContext() throws InterruptedException {
            delegate = Executors.newFixedThreadPool(1);
            FlowContextPropagatingExecutor executor = new FlowContextPropagatingExecutor(delegate);
            AtomicReference<FlowContext> observed = new AtomicReference<>();

            FlowContextHolder.FlowScope scope = FlowContextHolder.enter();
            try {
                FlowContext submitted = scope.context();
                executeAndAwait(executor, () -> observed.set(FlowContextHolder.snapshot()));

                assertThat(observed.get()).isEqualTo(submitted);
            } finally {
                scope.close();
            }
        }

        @Test
        @DisplayName("does not increase the depth of the propagated context")
        void doesNotIncreaseDepth() throws InterruptedException {
            delegate = Executors.newFixedThreadPool(1);
            FlowContextPropagatingExecutor executor = new FlowContextPropagatingExecutor(delegate);
            AtomicReference<Integer> observedDepth = new AtomicReference<>();

            FlowContextHolder.FlowScope outer = FlowContextHolder.enter();
            try {
                FlowContextHolder.FlowScope inner = FlowContextHolder.enter();
                try {
                    executeAndAwait(executor, () -> observedDepth.set(FlowContextHolder.snapshot().depth()));

                    assertThat(observedDepth.get()).isEqualTo(inner.context().depth());
                } finally {
                    inner.close();
                }
            } finally {
                outer.close();
            }
        }

        @Test
        @DisplayName("propagates null when nothing is active on the submitting thread")
        void propagatesNullWhenNothingActive() throws InterruptedException {
            delegate = Executors.newFixedThreadPool(1);
            FlowContextPropagatingExecutor executor = new FlowContextPropagatingExecutor(delegate);
            AtomicReference<FlowContext> observed = new AtomicReference<>(FlowContext.root());

            executeAndAwait(executor, () -> observed.set(FlowContextHolder.snapshot()));

            assertThat(observed.get()).isNull();
        }

        @Test
        @DisplayName("propagates the submitting thread's MDC context map onto the pool thread")
        void propagatesMdcContext() throws InterruptedException {
            delegate = Executors.newFixedThreadPool(1);
            FlowContextPropagatingExecutor executor = new FlowContextPropagatingExecutor(delegate);
            AtomicReference<String> observed = new AtomicReference<>();

            MDC.put("requestId", "req-123");
            executeAndAwait(executor, () -> observed.set(MDC.get("requestId")));

            assertThat(observed.get()).isEqualTo("req-123");
        }

        @Test
        @DisplayName("does not leak a propagated MDC context into a later, unrelated task on a reused thread")
        void doesNotLeakMdcBetweenTasks() throws InterruptedException {
            delegate = Executors.newFixedThreadPool(1);
            FlowContextPropagatingExecutor executor = new FlowContextPropagatingExecutor(delegate);
            AtomicReference<String> observedOnSecondTask = new AtomicReference<>("unset");

            MDC.put("requestId", "req-123");
            executeAndAwait(executor, () -> { });
            MDC.clear();

            executeAndAwait(executor, () -> observedOnSecondTask.set(MDC.get("requestId")));

            assertThat(observedOnSecondTask.get()).isNull();
        }

        @Test
        @DisplayName("truly restores the executor thread's own FlowContext, not merely masked by the next wrapped task's own adopt()")
        void trulyRestoresFlowContextOnExecutorThread() throws InterruptedException {
            delegate = Executors.newFixedThreadPool(1);
            FlowContextPropagatingExecutor executor = new FlowContextPropagatingExecutor(delegate);

            FlowContextHolder.FlowScope scope = FlowContextHolder.enter();
            try {
                executeAndAwait(executor, () -> { });
            } finally {
                scope.close();
            }

            // Submitted straight to the raw delegate, bypassing this class's own
            // adopt() call, so this observes whatever the previous wrapped task
            // actually left behind on the reused pool thread.
            AtomicReference<FlowContext> observedWithoutWrapper = new AtomicReference<>(FlowContext.root());
            CountDownLatch latch = new CountDownLatch(1);
            delegate.execute(() -> {
                observedWithoutWrapper.set(FlowContextHolder.snapshot());
                latch.countDown();
            });
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(observedWithoutWrapper.get()).isNull();
        }

        @Test
        @DisplayName("truly restores the executor thread's own MDC context, not merely masked by the next wrapped task's own adopt()")
        void trulyRestoresMdcOnExecutorThread() throws InterruptedException {
            delegate = Executors.newFixedThreadPool(1);
            FlowContextPropagatingExecutor executor = new FlowContextPropagatingExecutor(delegate);

            MDC.put("requestId", "req-123");
            executeAndAwait(executor, () -> { });
            MDC.clear();

            AtomicReference<String> observedWithoutWrapper = new AtomicReference<>("unset");
            CountDownLatch latch = new CountDownLatch(1);
            delegate.execute(() -> {
                observedWithoutWrapper.set(MDC.get("requestId"));
                latch.countDown();
            });
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(observedWithoutWrapper.get()).isNull();
        }

        @Test
        @DisplayName("does not leak a propagated context into a later, unrelated task on a reused thread")
        void doesNotLeakContextBetweenTasks() throws InterruptedException {
            delegate = Executors.newFixedThreadPool(1);
            FlowContextPropagatingExecutor executor = new FlowContextPropagatingExecutor(delegate);
            AtomicReference<FlowContext> observedOnSecondTask = new AtomicReference<>(FlowContext.root());

            FlowContextHolder.FlowScope scope = FlowContextHolder.enter();
            try {
                executeAndAwait(executor, () -> { });
            } finally {
                scope.close();
            }

            executeAndAwait(executor, () -> observedOnSecondTask.set(FlowContextHolder.snapshot()));

            assertThat(observedOnSecondTask.get()).isNull();
        }

        @Test
        @DisplayName("still restores the executor thread's context when the submitted task throws")
        void restoresContextEvenWhenTaskThrows() throws InterruptedException {
            delegate = Executors.newFixedThreadPool(1);
            FlowContextPropagatingExecutor executor = new FlowContextPropagatingExecutor(delegate);
            AtomicReference<FlowContext> observedAfterFailure = new AtomicReference<>(FlowContext.root());

            FlowContextHolder.FlowScope scope = FlowContextHolder.enter();
            try {
                CountDownLatch latch = new CountDownLatch(1);
                executor.execute(() -> {
                    try {
                        throw new IllegalStateException("boom");
                    } finally {
                        latch.countDown();
                    }
                });
                assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                scope.close();
            }

            executeAndAwait(executor, () -> observedAfterFailure.set(FlowContextHolder.snapshot()));

            assertThat(observedAfterFailure.get()).isNull();
        }
    }

    @Nested
    @DisplayName("on a virtual thread executor")
    class OnVirtualThreadExecutor {

        @Test
        @DisplayName("propagates the submitting thread's context onto the virtual thread")
        void propagatesContext() throws InterruptedException {
            delegate = Executors.newVirtualThreadPerTaskExecutor();
            FlowContextPropagatingExecutor executor = new FlowContextPropagatingExecutor(delegate);
            AtomicReference<FlowContext> observed = new AtomicReference<>();
            AtomicReference<Boolean> ranOnDifferentThread = new AtomicReference<>();
            Thread submittingThread = Thread.currentThread();

            FlowContextHolder.FlowScope scope = FlowContextHolder.enter();
            try {
                FlowContext submitted = scope.context();
                executeAndAwait(executor, () -> {
                    observed.set(FlowContextHolder.snapshot());
                    ranOnDifferentThread.set(Thread.currentThread() != submittingThread);
                });

                assertThat(ranOnDifferentThread.get()).as("task ran on a different (virtual) thread").isTrue();
                assertThat(observed.get()).isEqualTo(submitted);
            } finally {
                scope.close();
            }
        }
    }

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("rejects a null delegate")
        void rejectsNullDelegate() {
            assertThatThrownBy(() -> new FlowContextPropagatingExecutor(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
