package com.fayupable.logged.spring.security;

import org.jspecify.annotations.NonNull;
import org.springframework.core.task.TaskDecorator;
import org.springframework.web.context.request.RequestAttributes;

/**
 * {@link TaskDecorator} that carries the submitting thread's Spring Web
 * {@link RequestAttributes} into whatever thread Spring's {@code @Async}
 * infrastructure actually runs the task on.
 *
 * <p>The Spring Web/Spring counterpart to
 * {@code com.fayupable.logged.spring.aspect.FlowContextTaskDecorator}: that
 * class carries this library's own call-chain tracking across an
 * {@code @Async} boundary, this one carries the current HTTP request across
 * the same boundary. Combine both on the same executor if both are needed:
 *
 * <pre>{@code
 * @Bean
 * public TaskExecutor taskExecutor() {
 *     ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
 *     executor.setTaskDecorator(runnable ->
 *             new FlowContextTaskDecorator().decorate(
 *                     new RequestAttributesTaskDecorator().decorate(runnable)));
 *     executor.initialize();
 *     return executor;
 * }
 * }</pre>
 *
 * <p>Without this registration, this library's IP-based caller identity
 * resolution silently falls back to {@code "unknown"} for any nested
 * {@code @Logged} call made from within an {@code @Async} method dispatched
 * through the decorated executor.
 */
public final class RequestAttributesTaskDecorator implements TaskDecorator {

    /**
     * Captures the calling thread's current {@link RequestAttributes} and
     * returns a wrapped task that restores them before running
     * {@code runnable} and restores the executor thread's own previous
     * request attributes again afterward, regardless of whether
     * {@code runnable} completes normally or throws.
     *
     * @param runnable the task Spring's {@code @Async} infrastructure is
     *                  about to hand off to an executor thread
     * @return a task carrying the calling thread's request attributes
     */
    @Override
    public Runnable decorate(@NonNull Runnable runnable) {
        RequestAttributes capturedAttributes = RequestAttributesPropagation.capture();
        return () -> {
            Runnable restorePreviousAttributes = RequestAttributesPropagation.adopt(capturedAttributes);
            try {
                runnable.run();
            } finally {
                restorePreviousAttributes.run();
            }
        };
    }
}
