package com.fayupable.logged.spring.security;

import org.jspecify.annotations.NonNull;
import org.springframework.web.context.request.RequestAttributes;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * {@link Executor} decorator that carries the submitting thread's Spring Web
 * {@link RequestAttributes} over to whatever thread actually runs the
 * submitted task.
 *
 * <p>{@code com.fayupable.logged.spring.aspect.FlowContextPropagatingExecutor}
 * solves the equivalent problem for this library's own call-chain tracking.
 * That class deliberately does not also propagate {@link RequestAttributes},
 * so that it stays usable in applications with no Spring Web on the
 * classpath at all. This class fills that specific gap for applications
 * that do have Spring Web: without it, a nested {@code @Logged} call made
 * from inside work submitted through a plain, unwrapped executor loses
 * access to the current HTTP request entirely on the executor thread, and
 * this library's IP-based caller identity resolution silently falls back to
 * {@code "unknown"} for that nested call.
 *
 * <p>Wrap once, alongside {@code FlowContextPropagatingExecutor} if both are
 * needed, by nesting decorators around the same delegate:
 *
 * <pre>{@code
 * Executor propagating = new FlowContextPropagatingExecutor(
 *         new RequestAttributesPropagatingExecutor(realExecutor));
 * }</pre>
 *
 * <p>This class is deliberately not wired in automatically anywhere in this
 * library's auto-configuration: a consuming application decides which
 * executor should carry the current request across a thread hand-off, the
 * same way it decides which methods get {@code @Logged} in the first place.
 */
public final class RequestAttributesPropagatingExecutor implements Executor {

    private final Executor delegate;

    /**
     * Wraps {@code delegate} so that every task submitted through this
     * executor carries the submitting thread's {@link RequestAttributes}
     * into whichever thread {@code delegate} actually runs it on.
     *
     * @param delegate the executor that will actually run submitted tasks
     */
    public RequestAttributesPropagatingExecutor(Executor delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    /**
     * Captures the calling thread's current {@link RequestAttributes} and
     * submits a wrapped task to the delegate executor that restores them
     * before running {@code command} and restores the executor thread's own
     * previous request attributes again afterward, regardless of whether
     * {@code command} completes normally or throws.
     *
     * @param command the task to run
     */
    @Override
    public void execute(@NonNull Runnable command) {
        RequestAttributes capturedAttributes = RequestAttributesPropagation.capture();
        delegate.execute(() -> {
            Runnable restorePreviousAttributes = RequestAttributesPropagation.adopt(capturedAttributes);
            try {
                command.run();
            } finally {
                restorePreviousAttributes.run();
            }
        });
    }
}
