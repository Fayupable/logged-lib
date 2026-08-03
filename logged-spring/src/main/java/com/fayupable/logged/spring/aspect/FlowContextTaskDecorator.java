package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.model.FlowContext;
import org.jspecify.annotations.NonNull;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * {@link TaskDecorator} that carries the submitting thread's
 * {@link FlowContext} and {@link org.slf4j.MDC} context into whatever thread
 * Spring's {@code @Async} infrastructure actually runs the task on.
 *
 * <p>Unlike {@link FlowContextPropagatingExecutor}, which a consuming
 * application wraps around an {@link java.util.concurrent.Executor} it
 * manages directly, {@code @Async} methods are dispatched by Spring's own
 * {@link org.springframework.core.task.TaskExecutor} machinery, which the
 * calling code never sees or controls directly. {@link TaskDecorator} is
 * the extension point Spring itself provides for exactly this situation: it
 * decorates every {@link Runnable} submitted to a
 * {@link org.springframework.core.task.support.TaskExecutorAdapter} or
 * {@code ThreadPoolTaskExecutor} before it is dispatched, regardless of
 * which underlying thread pool executes it.
 *
 * <p>This class deliberately does not wire itself into every
 * {@code TaskExecutor} bean automatically. Auto-attaching to any executor
 * found in the application context would decorate executors the consuming
 * application did not intend for this library to touch, which is a
 * surprising, hard-to-diagnose side effect. Instead, a consuming
 * application registers this decorator explicitly on the specific executor
 * it wants {@code @Logged} call chains to survive across:
 *
 * <pre>{@code
 * @Bean
 * public TaskExecutor taskExecutor() {
 *     ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
 *     executor.setTaskDecorator(new FlowContextTaskDecorator());
 *     executor.initialize();
 *     return executor;
 * }
 * }</pre>
 *
 * <p>Without this registration, a {@code @Logged} method invoked through
 * {@code @Async} starts a brand new call chain instead of continuing the
 * caller's: the trace id changes and the depth resets to zero, breaking the
 * ability to reconstruct the full call chain from log output.
 */
public final class FlowContextTaskDecorator implements TaskDecorator {

    /**
     * Captures the calling thread's current {@link FlowContext} and MDC
     * context map, and returns a wrapped task that restores both before
     * running {@code runnable} and restores the executor thread's own
     * previous state again afterward, regardless of whether {@code runnable}
     * completes normally or throws.
     *
     * <p>The MDC context map is captured and restored in full, not limited
     * to {@link LoggedMdcKeys}: see {@link MdcContextPropagation} for why.
     *
     * @param runnable the task Spring's {@code @Async} infrastructure is
     *                  about to hand off to an executor thread
     * @return a task carrying the calling thread's flow context and MDC
     */
    @Override
    public Runnable decorate(@NonNull Runnable runnable) {
        FlowContext capturedContext = FlowContextHolder.snapshot();
        Map<String, String> capturedMdc = MdcContextPropagation.capture();
        return () -> {
            Runnable restorePreviousContext = FlowContextHolder.adopt(capturedContext);
            Runnable restorePreviousMdc = MdcContextPropagation.adopt(capturedMdc);
            try {
                runnable.run();
            } finally {
                restorePreviousContext.run();
                restorePreviousMdc.run();
            }
        };
    }
}
