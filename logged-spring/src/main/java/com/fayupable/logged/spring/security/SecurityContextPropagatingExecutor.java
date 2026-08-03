package com.fayupable.logged.spring.security;

import org.jspecify.annotations.NonNull;
import org.springframework.security.core.context.SecurityContext;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * {@link Executor} decorator that carries the submitting thread's Spring
 * Security {@link SecurityContext} over to whatever thread actually runs the
 * submitted task.
 *
 * <p>{@code com.fayupable.logged.spring.aspect.FlowContextPropagatingExecutor}
 * solves the equivalent problem for this library's own call-chain tracking.
 * That class deliberately does not also propagate {@link SecurityContext},
 * so that it stays usable in applications with no Spring Security on the
 * classpath at all. This class fills that specific gap for applications
 * that do have Spring Security: without it, a nested {@code @Logged} call
 * made from inside work submitted through a plain, unwrapped executor loses
 * the authenticated user entirely on the executor thread, and this
 * library's caller identity resolution silently falls back to an IP address
 * or {@code "unknown"} for that nested call — losing *who* made the call,
 * not just a secondary detail.
 *
 * <p>Wrap once, alongside {@code FlowContextPropagatingExecutor} if both are
 * needed, by nesting decorators around the same delegate:
 *
 * <pre>{@code
 * Executor propagating = new FlowContextPropagatingExecutor(
 *         new SecurityContextPropagatingExecutor(realExecutor));
 * }</pre>
 *
 * <p>This class is deliberately not wired in automatically anywhere in this
 * library's auto-configuration: a consuming application decides which
 * executor should carry the current authenticated user across a thread
 * hand-off, the same way it decides which methods get {@code @Logged} in
 * the first place.
 */
public final class SecurityContextPropagatingExecutor implements Executor {

    private final Executor delegate;

    /**
     * Wraps {@code delegate} so that every task submitted through this
     * executor carries the submitting thread's {@link SecurityContext} into
     * whichever thread {@code delegate} actually runs it on.
     *
     * @param delegate the executor that will actually run submitted tasks
     */
    public SecurityContextPropagatingExecutor(Executor delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    /**
     * Captures the calling thread's current {@link SecurityContext} and
     * submits a wrapped task to the delegate executor that restores it
     * before running {@code command} and restores the executor thread's own
     * previous security context again afterward, regardless of whether
     * {@code command} completes normally or throws.
     *
     * @param command the task to run
     */
    @Override
    public void execute(@NonNull Runnable command) {
        SecurityContext capturedContext = SecurityContextPropagation.capture();
        delegate.execute(() -> {
            Runnable restorePreviousContext = SecurityContextPropagation.adopt(capturedContext);
            try {
                command.run();
            } finally {
                restorePreviousContext.run();
            }
        });
    }
}
