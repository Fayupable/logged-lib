package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.annotation.Logged;
import com.fayupable.logged.core.model.MethodInvocationEvent;
import com.fayupable.logged.core.port.IClientInfoPort;
import com.fayupable.logged.core.port.InvocationEventEmitter;
import com.fayupable.logged.core.port.MetricsRecorder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Spring AOP interceptor that implements the behavior described by
 * {@link Logged}.
 *
 * <p>This is a proxy-based Spring AOP aspect, not a full AspectJ weaving
 * aspect. As a consequence, it only intercepts calls made from outside the
 * proxied bean: a method calling another {@code @Logged} method on
 * {@code this} bypasses the proxy and is not intercepted. This is a known
 * limitation of Spring AOP and must be documented for library consumers.
 *
 * <p>For every intercepted call, this aspect always records an invocation
 * metric through {@link MetricsRecorder}, regardless of sampling or
 * threshold settings, so that dashboards and alerts remain accurate.
 * Whether a {@link MethodInvocationEvent} is additionally emitted through
 * {@link InvocationEventEmitter} is decided by {@link EmissionPolicy}: a
 * call is emitted when it failed, exceeded its configured slow threshold,
 * or was selected by random sampling. This ensures that failures and slow
 * calls are never silently dropped by a low sample rate, while still
 * reducing log volume for fast, high-traffic, successful calls.
 *
 * <p>This aspect maintains a call-chain position for nested {@code @Logged}
 * calls on the same thread through {@link FlowContextHolder}, so that log
 * output can be reconstructed as a call chain and a failure can be traced
 * back to exactly where in that chain it occurred.
 *
 * <p>A failure inside {@link MetricsRecorder#record} or
 * {@link InvocationEventEmitter#emit}, whether from a bundled implementation
 * or a custom one supplied by the consuming application, is caught and
 * logged rather than allowed to propagate. Without this, a misbehaving
 * observability collaborator could replace the intercepted method's own
 * exception in a {@code finally} block, hiding the real failure behind an
 * unrelated one from this library's own bookkeeping.
 */
@Aspect
@Component
public class LoggedAspect {

    private static final Logger log = LoggerFactory.getLogger(LoggedAspect.class);

    private final InvocationEventEmitter eventEmitter;
    private final MetricsRecorder metricsRecorder;
    private final IClientInfoPort clientInfoPort;

    public LoggedAspect(InvocationEventEmitter eventEmitter, MetricsRecorder metricsRecorder, IClientInfoPort clientInfoPort) {
        this.eventEmitter = eventEmitter;
        this.metricsRecorder = metricsRecorder;
        this.clientInfoPort = clientInfoPort;
    }

    /**
     * Intercepts a {@link Logged}-annotated method call, measuring its
     * duration and outcome, and delegating the emission decision and
     * call-chain tracking to their respective collaborators.
     *
     * @param pjp    the join point representing the intercepted call
     * @param logged the {@link Logged} annotation present on the
     *               intercepted method
     * @return the value returned by the intercepted method
     * @throws Throwable whatever the intercepted method throws; this
     *                    aspect never swallows an exception
     */
    @Around("@annotation(logged)")
    public Object logInvocation(ProceedingJoinPoint pjp, Logged logged) throws Throwable {
        String className = pjp.getSignature().getDeclaringType().getSimpleName();
        String methodName = pjp.getSignature().getName();
        long start = System.nanoTime();
        boolean success = false;
        String exceptionType = null;

        FlowContextHolder.FlowScope flowScope = FlowContextHolder.enter();
        try {
            Object result = pjp.proceed();
            success = true;
            return result;
        } catch (Throwable t) {
            exceptionType = t.getClass().getSimpleName();
            throw t;
        } finally {
            long durationNanos = System.nanoTime() - start;
            recordObservability(logged, className, methodName, durationNanos, success, exceptionType, flowScope);
            flowScope.close();
        }
    }

    private void recordObservability(Logged logged, String className, String methodName, long durationNanos,
                                      boolean success, String exceptionType, FlowContextHolder.FlowScope flowScope) {
        try {
            metricsRecorder.record(className, methodName, durationNanos, success, exceptionType);

            if (EmissionPolicy.shouldEmit(logged, durationNanos, success)) {
                eventEmitter.emit(new MethodInvocationEvent(
                        className, methodName, Instant.now(), durationNanos, success, exceptionType,
                        clientInfoPort.resolveCallerIdentity(), flowScope.context().traceId(), flowScope.context().depth()
                ));
            }
        } catch (RuntimeException e) {
            log.warn("Ignoring failure while recording observability data for {}.{}", className, methodName, e);
        }
    }
}