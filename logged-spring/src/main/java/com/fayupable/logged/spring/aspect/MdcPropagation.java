package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.model.FlowContext;
import org.slf4j.MDC;

/**
 * Writes {@link LoggedMdcKeys} into the current thread's {@link MDC} for the
 * duration of a single {@code @Logged} invocation, and restores whatever was
 * there before once that invocation ends.
 *
 * <p>Package-private: this is an internal detail of {@link LoggedAspect}
 * mirroring {@link FlowContextHolder}'s enter/close lifecycle, not part of
 * this library's public API. {@link LoggedMdcKeys} is the public surface a
 * consuming application actually interacts with.
 *
 * <p>Previous values are restored rather than simply removed, for the same
 * reason {@link FlowContextHolder} restores rather than clears: a nested
 * {@code @Logged} call must not permanently erase the outer call's MDC
 * entries once the nested call returns. Restoring instead of removing keeps
 * this correct regardless of nesting depth.
 */
final class MdcPropagation {

    private MdcPropagation() {
    }

    /**
     * Writes {@code context}, {@code className}, and {@code methodName} into
     * the current thread's MDC under {@link LoggedMdcKeys}, and returns a
     * {@link Runnable} that restores whatever value each key held before
     * this call, once run.
     *
     * @param context    the call-chain position of the invocation currently
     *                   starting
     * @param className  the class name resolved for this invocation
     * @param methodName the method name resolved for this invocation
     * @return a {@link Runnable} that undoes this write; must be run once
     *         the invocation ends, typically in a {@code finally} block
     */
    static Runnable push(FlowContext context, String className, String methodName) {
        String previousTraceId = MDC.get(LoggedMdcKeys.TRACE_ID);
        String previousDepth = MDC.get(LoggedMdcKeys.DEPTH);
        String previousClassName = MDC.get(LoggedMdcKeys.CLASS_NAME);
        String previousMethodName = MDC.get(LoggedMdcKeys.METHOD_NAME);

        MDC.put(LoggedMdcKeys.TRACE_ID, context.traceId());
        MDC.put(LoggedMdcKeys.DEPTH, String.valueOf(context.depth()));
        MDC.put(LoggedMdcKeys.CLASS_NAME, className);
        MDC.put(LoggedMdcKeys.METHOD_NAME, methodName);

        return () -> {
            restore(LoggedMdcKeys.TRACE_ID, previousTraceId);
            restore(LoggedMdcKeys.DEPTH, previousDepth);
            restore(LoggedMdcKeys.CLASS_NAME, previousClassName);
            restore(LoggedMdcKeys.METHOD_NAME, previousMethodName);
        };
    }

    private static void restore(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }
}
