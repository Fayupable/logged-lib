package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.model.FlowContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FlowContextTaskDecorator")
class FlowContextTaskDecoratorTest {

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
        MDC.clear();
    }

    private ThreadPoolTaskExecutor newDecoratedExecutor() {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(1);
        taskExecutor.setMaxPoolSize(1);
        taskExecutor.setTaskDecorator(new FlowContextTaskDecorator());
        taskExecutor.initialize();
        return taskExecutor;
    }

    private void submitAndAwait(ThreadPoolTaskExecutor taskExecutor, Runnable command) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        taskExecutor.execute(() -> {
            try {
                command.run();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).as("submitted task completed in time").isTrue();
    }

    @Nested
    @DisplayName("as a Spring TaskExecutor's TaskDecorator")
    class AsTaskExecutorDecorator {

        @Test
        @DisplayName("propagates the submitting thread's context onto the executor thread")
        void propagatesContext() throws InterruptedException {
            executor = newDecoratedExecutor();
            AtomicReference<FlowContext> observed = new AtomicReference<>();

            FlowContextHolder.FlowScope scope = FlowContextHolder.enter();
            try {
                FlowContext submitted = scope.context();
                submitAndAwait(executor, () -> observed.set(FlowContextHolder.snapshot()));

                assertThat(observed.get()).isEqualTo(submitted);
            } finally {
                scope.close();
            }
        }

        @Test
        @DisplayName("propagates null when nothing is active on the submitting thread")
        void propagatesNullWhenNothingActive() throws InterruptedException {
            executor = newDecoratedExecutor();
            AtomicReference<FlowContext> observed = new AtomicReference<>(FlowContext.root());

            submitAndAwait(executor, () -> observed.set(FlowContextHolder.snapshot()));

            assertThat(observed.get()).isNull();
        }

        @Test
        @DisplayName("propagates the submitting thread's MDC context map onto the executor thread")
        void propagatesMdcContext() throws InterruptedException {
            executor = newDecoratedExecutor();
            AtomicReference<String> observed = new AtomicReference<>();

            MDC.put("requestId", "req-123");
            submitAndAwait(executor, () -> observed.set(MDC.get("requestId")));

            assertThat(observed.get()).isEqualTo("req-123");
        }

        @Test
        @DisplayName("does not leak a propagated MDC context into a later, unrelated task on a reused thread")
        void doesNotLeakMdcBetweenTasks() throws InterruptedException {
            executor = newDecoratedExecutor();
            AtomicReference<String> observedOnSecondTask = new AtomicReference<>("unset");

            MDC.put("requestId", "req-123");
            submitAndAwait(executor, () -> { });
            MDC.clear();

            submitAndAwait(executor, () -> observedOnSecondTask.set(MDC.get("requestId")));

            assertThat(observedOnSecondTask.get()).isNull();
        }

        @Test
        @DisplayName("truly restores the executor thread's own FlowContext, not merely masked by the next wrapped task's own adopt()")
        void trulyRestoresFlowContextOnExecutorThread() throws InterruptedException {
            executor = newDecoratedExecutor();

            FlowContextHolder.FlowScope scope = FlowContextHolder.enter();
            try {
                submitAndAwait(executor, () -> { });
            } finally {
                scope.close();
            }

            // Decorator removed so this probe observes the reused pool thread's
            // raw leftover state, not a value freshly re-adopted by decorate().
            executor.setTaskDecorator(null);
            AtomicReference<FlowContext> observedWithoutDecoration = new AtomicReference<>(FlowContext.root());
            submitAndAwait(executor, () -> observedWithoutDecoration.set(FlowContextHolder.snapshot()));

            assertThat(observedWithoutDecoration.get()).isNull();
        }

        @Test
        @DisplayName("truly restores the executor thread's own MDC context, not merely masked by the next wrapped task's own adopt()")
        void trulyRestoresMdcOnExecutorThread() throws InterruptedException {
            executor = newDecoratedExecutor();

            MDC.put("requestId", "req-123");
            submitAndAwait(executor, () -> { });
            MDC.clear();

            executor.setTaskDecorator(null);
            AtomicReference<String> observedWithoutDecoration = new AtomicReference<>("unset");
            submitAndAwait(executor, () -> observedWithoutDecoration.set(MDC.get("requestId")));

            assertThat(observedWithoutDecoration.get()).isNull();
        }

        @Test
        @DisplayName("does not leak a propagated context into a later, unrelated task on a reused thread")
        void doesNotLeakContextBetweenTasks() throws InterruptedException {
            executor = newDecoratedExecutor();
            AtomicReference<FlowContext> observedOnSecondTask = new AtomicReference<>(FlowContext.root());

            FlowContextHolder.FlowScope scope = FlowContextHolder.enter();
            try {
                submitAndAwait(executor, () -> { });
            } finally {
                scope.close();
            }

            submitAndAwait(executor, () -> observedOnSecondTask.set(FlowContextHolder.snapshot()));

            assertThat(observedOnSecondTask.get()).isNull();
        }

        @Test
        @DisplayName("still restores the executor thread's context when the submitted task throws")
        void restoresContextEvenWhenTaskThrows() throws InterruptedException {
            executor = newDecoratedExecutor();
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

            submitAndAwait(executor, () -> observedAfterFailure.set(FlowContextHolder.snapshot()));

            assertThat(observedAfterFailure.get()).isNull();
        }
    }

    @Nested
    @DisplayName("decorate()")
    class Decorate {

        @Test
        @DisplayName("returns a task that runs the original runnable")
        void runsOriginalRunnable() {
            TaskDecorator decorator = new FlowContextTaskDecorator();
            AtomicReference<Boolean> ran = new AtomicReference<>(false);

            decorator.decorate(() -> ran.set(true)).run();

            assertThat(ran.get()).isTrue();
        }
    }
}
