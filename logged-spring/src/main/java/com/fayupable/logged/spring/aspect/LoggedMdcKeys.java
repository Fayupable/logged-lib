package com.fayupable.logged.spring.aspect;

import org.slf4j.MDC;

/**
 * The {@link MDC} key names this library writes to while a {@code @Logged}
 * method is executing.
 *
 * <p>These keys are public and stable: a consuming application references
 * them directly in its own logging configuration (a Logback pattern layout,
 * a JSON encoder's field mappings, and so on) to have every log statement
 * made during a {@code @Logged} call — not just the summary line this
 * library itself emits — automatically carry the call's trace id, depth,
 * class, and method name. Without this, correlating an application's own
 * {@code log.info(...)} calls with a specific invocation in a structured
 * logging backend (Loki, ELK) requires manually threading that information
 * through every log statement by hand.
 *
 * <p>Every key is prefixed with {@code logged.} specifically to avoid
 * colliding with an MDC key a consuming application, or another library,
 * already uses for its own purposes (a bare {@code traceId} key, for
 * example, is common enough that a collision would be likely without this
 * prefix).
 */
public final class LoggedMdcKeys {

    /**
     * The {@link com.fayupable.logged.core.model.FlowContext#traceId()}
     * shared by every call in the same call chain.
     */
    public static final String TRACE_ID = "logged.traceId";

    /**
     * The {@link com.fayupable.logged.core.model.FlowContext#depth()} of
     * the currently executing call within its chain, as a string.
     */
    public static final String DEPTH = "logged.depth";

    /**
     * The class name resolved for the currently executing invocation, the
     * same value reported as {@link com.fayupable.logged.core.model.MethodInvocationEvent#className()}.
     */
    public static final String CLASS_NAME = "logged.className";

    /**
     * The method name resolved for the currently executing invocation, the
     * same value reported as {@link com.fayupable.logged.core.model.MethodInvocationEvent#methodName()}.
     */
    public static final String METHOD_NAME = "logged.methodName";

    /**
     * The caller's IP address, the same value reported as
     * {@link com.fayupable.logged.core.model.MethodInvocationEvent#callerIp()}.
     * Only written while executing a method annotated with
     * {@code @Logged(includeIp = true)}; absent for every other invocation.
     */
    public static final String CALLER_IP = "logged.callerIp";

    /**
     * A coarse description of the caller's device, the same value reported
     * as {@link com.fayupable.logged.core.model.MethodInvocationEvent#callerDevice()}.
     * Only written while executing a method for which device info is
     * requested ({@code @Logged(includeDevice = true)} or
     * {@code logged.client-info.include-device=true}); absent otherwise.
     */
    public static final String CALLER_DEVICE = "logged.callerDevice";

    private LoggedMdcKeys() {
    }
}
