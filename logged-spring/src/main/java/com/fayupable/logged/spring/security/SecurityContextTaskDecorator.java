package com.fayupable.logged.spring.security;

import org.jspecify.annotations.NonNull;
import org.springframework.core.task.TaskDecorator;
import org.springframework.security.core.context.SecurityContext;

/**
 * {@link TaskDecorator} that carries the submitting thread's Spring Security
 * {@link SecurityContext} into whatever thread Spring's {@code @Async}
 * infrastructure actually runs the task on.
 *
 * <p>The Spring Security counterpart to
 * {@code com.fayupable.logged.spring.aspect.FlowContextTaskDecorator}: that
 * class carries this library's own call-chain tracking across an
 * {@code @Async} boundary, this one carries the authenticated user across
 * the same boundary. Combine both on the same executor if both are needed:
 *
 * <pre>{@code
 * @Bean
 * public TaskExecutor taskExecutor() {
 *     ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
 *     executor.setTaskDecorator(runnable ->
 *             new FlowContextTaskDecorator().decorate(
 *                     new SecurityContextTaskDecorator().decorate(runnable)));
 *     executor.initialize();
 *     return executor;
 * }
 * }</pre>
 *
 * <p>Without this registration, this library's caller identity resolution
 * silently falls back to an IP address or {@code "unknown"} for any nested
 * {@code @Logged} call made from within an {@code @Async} method dispatched
 * through the decorated executor, losing the authenticated user entirely.
 */
public final class SecurityContextTaskDecorator implements TaskDecorator {

    /**
     * Captures the calling thread's current {@link SecurityContext} and
     * returns a wrapped task that restores it before running
     * {@code runnable} and restores the executor thread's own previous
     * security context again afterward, regardless of whether
     * {@code runnable} completes normally or throws.
     *
     * @param runnable the task Spring's {@code @Async} infrastructure is
     *                  about to hand off to an executor thread
     * @return a task carrying the calling thread's security context
     */
    @Override
    public Runnable decorate(@NonNull Runnable runnable) {
        SecurityContext capturedContext = SecurityContextPropagation.capture();
        return () -> {
            Runnable restorePreviousContext = SecurityContextPropagation.adopt(capturedContext);
            try {
                runnable.run();
            } finally {
                restorePreviousContext.run();
            }
        };
    }
}
