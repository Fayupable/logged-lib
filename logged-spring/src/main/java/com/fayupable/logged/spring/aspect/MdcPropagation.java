package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.model.FlowContext;
import com.fayupable.logged.spring.support.LogSafe;
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
     * Writes {@code context}, {@code className}, {@code methodName}, and,
     * when present, {@code callerIp} and {@code callerDevice} into the current thread's MDC under
     * {@link LoggedMdcKeys}, and returns a {@link Runnable} that restores
     * whatever value each key held before this call, once run.
     *
     * @param context    the call-chain position of the invocation currently
     *                   starting
     * @param className  the class name resolved for this invocation
     * @param methodName the method name resolved for this invocation
     * @param callerIp   the caller's IP address resolved for this
     *                   invocation, or {@code null} if
     *                   {@code @Logged(includeIp = true)} was not set on the
     *                   invoked method; when {@code null}, {@link LoggedMdcKeys#CALLER_IP}
     *                   is left untouched rather than written as an empty
     *                   or missing value
     * @param callerDevice the caller's coarse device description, or
     *                   {@code null} if device info was not requested; when
     *                   {@code null}, {@link LoggedMdcKeys#CALLER_DEVICE} is
     *                   left untouched, the same as {@code callerIp}. Both are
     *                   written through {@link LogSafe#singleLine(String)},
     *                   since the application's own log lines use these
     *                   MDC values in formats this library does not control
     * @return a {@link Runnable} that undoes this write; must be run once
     *         the invocation ends, typically in a {@code finally} block
     */
    static Runnable push(FlowContext context, String className, String methodName, String callerIp,
                         String callerDevice) {
        String previousTraceId = MDC.get(LoggedMdcKeys.TRACE_ID);
        String previousDepth = MDC.get(LoggedMdcKeys.DEPTH);
        String previousClassName = MDC.get(LoggedMdcKeys.CLASS_NAME);
        String previousMethodName = MDC.get(LoggedMdcKeys.METHOD_NAME);
        String previousCallerIp = MDC.get(LoggedMdcKeys.CALLER_IP);
        String previousCallerDevice = MDC.get(LoggedMdcKeys.CALLER_DEVICE);

        MDC.put(LoggedMdcKeys.TRACE_ID, context.traceId());
        MDC.put(LoggedMdcKeys.DEPTH, String.valueOf(context.depth()));
        MDC.put(LoggedMdcKeys.CLASS_NAME, className);
        MDC.put(LoggedMdcKeys.METHOD_NAME, methodName);
        putIfPresent(LoggedMdcKeys.CALLER_IP, LogSafe.singleLine(callerIp));
        putIfPresent(LoggedMdcKeys.CALLER_DEVICE, LogSafe.singleLine(callerDevice));

        return () -> {
            restore(LoggedMdcKeys.TRACE_ID, previousTraceId);
            restore(LoggedMdcKeys.DEPTH, previousDepth);
            restore(LoggedMdcKeys.CLASS_NAME, previousClassName);
            restore(LoggedMdcKeys.METHOD_NAME, previousMethodName);
            restore(LoggedMdcKeys.CALLER_IP, previousCallerIp);
            restore(LoggedMdcKeys.CALLER_DEVICE, previousCallerDevice);
        };
    }

    private static void putIfPresent(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }

    private static void restore(String key, String previousValue) {
        if (previousValue == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previousValue);
        }
    }
}
