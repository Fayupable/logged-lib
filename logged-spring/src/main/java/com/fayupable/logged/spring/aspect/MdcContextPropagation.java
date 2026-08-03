package com.fayupable.logged.spring.aspect;

import org.slf4j.MDC;

import java.util.Map;

/**
 * Captures and restores the current thread's entire {@link MDC} context map
 * across a thread boundary, mirroring {@link FlowContextHolder}'s
 * {@code snapshot()}/{@code adopt()} pair but for the full MDC map rather
 * than a single {@link com.fayupable.logged.core.model.FlowContext}.
 *
 * <p>This intentionally captures every MDC entry present on the submitting
 * thread, not only {@link LoggedMdcKeys}. {@link FlowContextPropagatingExecutor}
 * and {@link FlowContextTaskDecorator} are general-purpose thread-boundary
 * crossers: a consuming application's own MDC entries (a request id set by
 * a web filter, for example) are just as much at risk of being lost across
 * a thread hop as this library's own, and there is no reason this library's
 * propagation should only rescue its own keys while leaving everything else
 * behind.
 *
 * <p>Package-private: this is an internal detail shared by
 * {@link FlowContextPropagatingExecutor} and {@link FlowContextTaskDecorator},
 * not part of this library's public API.
 */
final class MdcContextPropagation {

    private MdcContextPropagation() {
    }

    /**
     * Returns a copy of the current thread's MDC context map, or
     * {@code null} if it has none.
     *
     * @return the current thread's MDC context map, or {@code null}
     */
    static Map<String, String> capture() {
        return MDC.getCopyOfContextMap();
    }

    /**
     * Makes {@code context} the active MDC context map on the calling
     * thread and returns a {@link Runnable} that undoes this, restoring
     * whatever map was active before this call.
     *
     * @param context the MDC context map captured by {@link #capture()} on
     *                the thread that is handing off work, or {@code null}
     *                if that thread had none
     * @return a {@link Runnable} that restores this thread's previous MDC
     *         context map; never {@code null}
     */
    static Runnable adopt(Map<String, String> context) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
        return () -> {
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        };
    }
}
