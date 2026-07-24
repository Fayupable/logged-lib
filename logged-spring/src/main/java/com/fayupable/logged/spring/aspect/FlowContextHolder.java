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
}