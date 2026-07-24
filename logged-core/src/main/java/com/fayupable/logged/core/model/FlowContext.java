package com.fayupable.logged.core.model;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Tracks the position of the current method invocation within a chain of
 * nested {@code @Logged} calls on the same thread.
 *
 * <p>When a {@code @Logged} method calls another {@code @Logged} method
 * directly or indirectly, this context lets an interceptor recognize that
 * both calls belong to the same logical operation and record their relative
 * order. Combined with {@link MethodInvocationEvent}, this makes it
 * possible to reconstruct a call chain such as {@code methodA -> methodB ->
 * methodC} in log output, and to see exactly where in that chain a failure
 * occurred.
 *
 * <p>This class holds only a correlation identifier and a depth counter; it
 * does not store method arguments, return values, or any other invocation
 * data, consistent with the rest of this library.
 *
 * <p>Instances are managed per-thread by the interceptor, typically through
 * a {@link ThreadLocal}. This class itself has no thread-affinity logic; it
 * is a plain, immutable-per-call data holder.
 *
 * @param traceId a correlation identifier shared by every call in the same
 *                chain, generated once when the chain's root method is
 *                entered
 * @param depth   the zero-based nesting level of the current call within
 *                the chain; the root call has depth 0, a call it makes
 *                directly has depth 1, and so on
 */
public record FlowContext(String traceId, int depth) {

    /**
     * Creates the root context for a new call chain, deriving the trace id
     * from a single 64-bit random value.
     *
     * <p>This identifier only needs to be unique enough to avoid confusing
     * concurrent call chains in log output; it is not a security token. A
     * single value from {@link ThreadLocalRandom} is used deliberately
     * instead of {@link java.util.UUID#randomUUID()}, since the latter
     * relies on {@link java.security.SecureRandom} internally and produces
     * twice the amount of random data than this use case requires.
     * {@link ThreadLocalRandom} avoids any shared-state locking, making
     * this call cheap even under high concurrency.
     *
     * @return a new root {@link FlowContext}
     */
    public static FlowContext root() {
        String traceId = Long.toHexString(ThreadLocalRandom.current().nextLong());
        return new FlowContext(traceId, 0);
    }

    /**
     * Creates the context for a nested call one level deeper than this one,
     * keeping the same {@link #traceId()} so it remains part of the same
     * chain.
     *
     * @return a new {@link FlowContext} with the same trace id and depth
     *         incremented by one
     */
    public FlowContext next() {
        return new FlowContext(traceId, depth + 1);
    }
}