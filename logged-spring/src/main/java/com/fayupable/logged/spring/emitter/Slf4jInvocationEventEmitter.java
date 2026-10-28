package com.fayupable.logged.spring.emitter;

import com.fayupable.logged.core.model.MethodInvocationEvent;
import com.fayupable.logged.core.port.InvocationEventEmitter;
import com.fayupable.logged.spring.support.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link InvocationEventEmitter} that writes each event as a single,
 * human-readable line through SLF4J.
 *
 * <p>Successful calls are logged at {@code INFO}, failed calls at
 * {@code WARN}, since a failure surfacing through a {@code @Logged} method
 * is operationally significant even though the exception itself already
 * propagates to the caller. The exception message is never included, only
 * its simple class name, consistent with {@link MethodInvocationEvent}.
 *
 * <p>The trace id and depth carried by the event are only appended to the
 * log line when {@link MethodInvocationEvent#depth()} is greater than zero,
 * that is, when the call is part of a chain of nested {@code @Logged}
 * invocations. Root-level, non-nested calls are logged without this extra
 * detail, keeping the common case concise.
 *
 * <p>{@link MethodInvocationEvent#rootCauseType()} is only appended to a
 * failure line when it differs from {@link MethodInvocationEvent#exceptionType()},
 * that is, when the thrown exception actually wraps a different underlying
 * cause. When the two are equal, showing the root cause a second time would
 * add nothing, so it is omitted to keep the common case concise.
 *
 * <p>{@link MethodInvocationEvent#callerIp()} is only appended when present,
 * that is, only for the security-sensitive methods explicitly annotated
 * with {@code @Logged(includeIp = true)}. It is deliberately shown alongside
 * {@link MethodInvocationEvent#callerIdentity()} rather than only in its
 * place, since the two answer different questions ("who" versus "from
 * where") that both matter for the operations this attribute is meant for.
 *
 * <p>{@link MethodInvocationEvent#callerDevice()} is likewise only appended
 * when present, as {@code device="Windows / Chrome 129"}. It is quoted
 * because it contains spaces; the value itself is built only from a fixed
 * set of names and digits, so it can never contain a quote or a line break.
 *
 * <p>Caller identity, IP and device can originate outside the application
 * (a username chosen at registration, an {@code X-Forwarded-For} entry, a
 * custom resolver's result), so each is passed through
 * {@link LogSafe#singleLine(String)} before being written: a line break in
 * any of them is shown as {@code \n} instead of starting a forged log line.
 * Values without control characters are written unchanged.
 */
public class Slf4jInvocationEventEmitter implements InvocationEventEmitter {

    private static final Logger log = LoggerFactory.getLogger(Slf4jInvocationEventEmitter.class);

    @Override
    public void emit(MethodInvocationEvent event) {
        long durationMs = event.durationNanos() / 1_000_000;
        String callerIdentity = LogSafe.singleLine(event.callerIdentity());
        String ipSuffix = event.callerIp() != null ? " ip=" + LogSafe.singleLine(event.callerIp()) : "";
        String deviceSuffix = event.callerDevice() != null
                ? " device=\"" + LogSafe.singleLine(event.callerDevice()) + "\""
                : "";
        String chainSuffix = event.depth() > 0
                ? " (trace=" + event.traceId() + ", depth=" + event.depth() + ")"
                : "";

        if (event.success()) {
            log.info("[{}]{}{} {}.{} completed in {}ms{}",
                    callerIdentity, ipSuffix, deviceSuffix, event.className(), event.methodName(), durationMs, chainSuffix);
        } else {
            boolean rootCauseDiffers = event.rootCauseType() != null && !event.rootCauseType().equals(event.exceptionType());
            String causeSuffix = rootCauseDiffers ? " (caused by " + event.rootCauseType() + ")" : "";

            log.warn("[{}]{}{} {}.{} failed after {}ms - {}{}{}",
                    callerIdentity, ipSuffix, deviceSuffix, event.className(), event.methodName(), durationMs,
                    event.exceptionType(), causeSuffix, chainSuffix);
        }
    }
}
