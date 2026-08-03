package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.model.FlowContext;

/**
 * Manages the current thread's {@link FlowContext} across a nested chain of
 * {@code @Logged} calls.
 *
 * <p>Package-private: this is an internal detail of {@link LoggedAspect},
 * not part of the library's public API.
 */
final class FlowContextHolder {

    private static final ThreadLocal<FlowContext> FLOW = new ThreadLocal<>();

    private FlowContextHolder() {
    }

    /**
     * Enters a new level of the call chain, deriving it from whatever
     * context is currently active on this thread, and returns a scope that
     * restores the previous context when closed.
     *
     * @return a scope to be closed once the intercepted call returns
     */
    static FlowScope enter() {
        FlowContext previous = FLOW.get();
        FlowContext current = previous == null ? FlowContext.root() : previous.next();
        FLOW.set(current);
        return new FlowScope(previous, current);
    }

    /**
     * Restores the thread's {@link FlowContext} to what it was before the
     * corresponding {@link #enter()} call, on {@link #close()}, and exposes
     * the context that applies to the call this scope was opened for.
     */
    static final class FlowScope implements AutoCloseable {

        private final FlowContext previous;
        private final FlowContext current;

        private FlowScope(FlowContext previous, FlowContext current) {
            this.previous = previous;
            this.current = current;
        }

        /**
         * Returns the {@link FlowContext} that applies to the call this
         * scope was opened for.
         *
         * @return the current call's flow context
         */
        FlowContext context() {
            return current;
        }

        @Override
        public void close() {
            if (previous == null) {
                FLOW.remove();
            } else {
                FLOW.set(previous);
            }
        }
    }

    /**
     * Returns the {@link FlowContext} currently active on this thread,
     * without modifying it, or {@code null} if no {@code @Logged} call is
     * in progress on this thread.
     *
     * <p>This is the read side of propagating a call chain across a thread
     * boundary: code about to hand work off to another thread (for example
     * submitting it to an {@link java.util.concurrent.Executor}) calls this
     * on the submitting thread to capture what should be restored once the
     * work actually runs. Because {@link FlowContext} is an immutable
     * record, the returned value is safe to pass to another thread without
     * any further synchronization.
     *
     * @return the active flow context, or {@code null} if none is active
     */
    static FlowContext snapshot() {
        return FLOW.get();
    }

    /**
     * Makes {@code context} the active {@link FlowContext} on the calling
     * thread and returns a {@link Runnable} that undoes this, restoring
     * whatever context was active before this call.
     *
     * <p>This is the write side of propagating a call chain across a thread
     * boundary, used together with {@link #snapshot()}: the thread that
     * will actually run the handed-off work calls this with the context
     * captured on the original thread, so that any nested {@code @Logged}
     * call made from within that work is recognized as part of the same
     * chain instead of starting a new one. Unlike {@link #enter()}, this
     * does not derive a deeper context via {@link FlowContext#next()} — the
     * handed-off work is a continuation of the same logical step, not a new
     * one, so the depth carried by {@code context} is reused as-is.
     *
     * <p>The returned {@link Runnable} must be run once the handed-off work
     * completes, typically in a {@code finally} block, so that a thread
     * reused by a pool (for example between two unrelated tasks) does not
     * keep leaking a stale context into whatever runs on it next.
     *
     * @param context the context captured by {@link #snapshot()} on the
     *                thread that is handing off work, or {@code null} if
     *                that thread had none active
     * @return a {@link Runnable} that restores this thread's previous
     *         context; never {@code null}
     */
    static Runnable adopt(FlowContext context) {
        FlowContext previous = FLOW.get();
        if (context == null) {
            FLOW.remove();
        } else {
            FLOW.set(context);
        }
        return () -> {
            if (previous == null) {
                FLOW.remove();
            } else {
                FLOW.set(previous);
            }
        };
    }
}