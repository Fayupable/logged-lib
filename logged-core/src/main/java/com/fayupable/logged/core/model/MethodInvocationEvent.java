package com.fayupable.logged.core.model;

import java.time.Instant;

/**
 * Immutable record describing a single method invocation captured by a
 * {@link com.fayupable.logged.core.annotation.Logged} interceptor.
 *
 * <p>This event deliberately contains no method arguments and no return
 * value. Only metadata about the call is recorded, so this event can be
 * logged, exported as a metric, or sent to any sink without any risk of
 * leaking sensitive data.
 *
 * @param className       the simple or fully qualified name of the class
 *                         declaring the invoked method
 * @param methodName       the name of the invoked method
 * @param timestamp        the instant at which the invocation started
 * @param durationNanos    the wall-clock duration of the invocation, in
 *                         nanoseconds
 * @param success          {@code true} if the method returned normally,
 *                         {@code false} if it threw an exception
 * @param exceptionType    the simple class name of the exception thrown by
 *                         the method, or {@code null} if the call succeeded;
 *                         the exception message is never captured here,
 *                         since it may contain sensitive data
 * @param callerIdentity   an opaque identifier describing who triggered the
 *                         call (for example an authenticated user id or a
 *                         client IP address), resolved by an adapter-specific
 *                         implementation
 * @param traceId          the {@link FlowContext#traceId()} shared by every
 *                         call in the same chain of nested {@code @Logged}
 *                         invocations, allowing log output to be
 *                         reconstructed as a single call chain
 * @param depth            the {@link FlowContext#depth()} of this
 *                         invocation within its call chain, with 0 marking
 *                         the root call
 */
public record MethodInvocationEvent(
        String className,
        String methodName,
        Instant timestamp,
        long durationNanos,
        boolean success,
        String exceptionType,
        String callerIdentity,
        String traceId,
        int depth
) {
}