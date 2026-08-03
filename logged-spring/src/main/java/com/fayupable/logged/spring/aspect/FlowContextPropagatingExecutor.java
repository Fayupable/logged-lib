package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.model.FlowContext;
import org.jspecify.annotations.NonNull;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * {@link Executor} decorator that carries the submitting thread's
 * {@link FlowContext} and {@link org.slf4j.MDC} context over to whatever
 * thread actually runs the submitted task.
 *
 * <p>{@link FlowContextHolder} tracks call-chain position through a
 * {@link ThreadLocal}, which by definition only exists on the thread that
 * set it. As soon as work is handed off to a different thread — a
 * {@code @Async} method, a {@link java.util.concurrent.CompletableFuture}
 * running on a custom {@link Executor}, a task submitted to
 * {@link java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor()} —
 * that thread starts with no context of its own, and any {@code @Logged}
 * call made from within it is reported as the start of a brand new chain
 * instead of a continuation of the caller's chain. This class closes that
 * gap for any {@link Executor}-based hand-off, including virtual thread
 * executors: an {@link Executor} is an {@link Executor} regardless of the
 * kind of thread backing it, so no separate handling is needed for virtual
 * threads specifically.
 *
 * <p>Wrapping is a one-time setup step; call sites do not change:
 *
 * <pre>{@code
 * Executor virtualThreadExecutor =
 *         Executors.newVirtualThreadPerTaskExecutor();
 * Executor propagating =
 *         new FlowContextPropagatingExecutor(virtualThreadExecutor);
 *
 * // Inside a @Logged method:
 * propagating.execute(() -> {
 *     // Any @Logged call made here is recognized as part of the
 *     // caller's chain, at the same depth the caller was at.
 *     someOtherLoggedBean.doWork();
 * });
 * }</pre>
 *
 * <p>The context is captured once per {@link #execute(Runnable)} call, on
 * the calling thread, at the moment the task is submitted — not when it
 * starts running. This matters because a pooled {@link Executor} may not
 * run the task immediately; capturing eagerly guarantees the propagated
 * context reflects the caller's state at submission time, which is what a
 * human reading the resulting call chain would expect, rather than whatever
 * happened to be active on the calling thread later when the pool got
 * around to it.
 *
 * <p>This class is deliberately not a Spring bean and requires no
 * {@code ApplicationContext}: it only depends on {@link FlowContextHolder}
 * and the {@link Executor} it wraps, so it can decorate any executor a
 * consuming application already manages, including ones created outside of
 * Spring's control.
 */
public final class FlowContextPropagatingExecutor implements Executor {

    private final Executor delegate;

    /**
     * Wraps {@code delegate} so that every task submitted through this
     * executor carries the submitting thread's {@link FlowContext} into
     * whichever thread {@code delegate} actually runs it on.
     *
     * @param delegate the executor that will actually run submitted tasks
     */
    public FlowContextPropagatingExecutor(Executor delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    /**
     * Captures the calling thread's current {@link FlowContext} and MDC
     * context map, and submits a wrapped task to the delegate executor that
     * restores both before running {@code command} and restores the
     * executor thread's own previous state again afterward, regardless of
     * whether {@code command} completes normally or throws.
     *
     * <p>The MDC context map is captured and restored in full, not limited
     * to {@link LoggedMdcKeys}: see {@link MdcContextPropagation} for why.
     *
     * @param command the task to run
     */
    @Override
    public void execute(@NonNull Runnable command) {
        FlowContext capturedContext = FlowContextHolder.snapshot();
        Map<String, String> capturedMdc = MdcContextPropagation.capture();
        delegate.execute(() -> {
            Runnable restorePreviousContext = FlowContextHolder.adopt(capturedContext);
            Runnable restorePreviousMdc = MdcContextPropagation.adopt(capturedMdc);
            try {
                command.run();
            } finally {
                restorePreviousContext.run();
                restorePreviousMdc.run();
            }
        });
    }
}
