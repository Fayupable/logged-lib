package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.annotation.Logged;
import com.fayupable.logged.core.model.FlowContext;
import com.fayupable.logged.core.model.MethodInvocationEvent;
import com.fayupable.logged.core.port.IClientInfoPort;
import com.fayupable.logged.core.port.InvocationEventEmitter;
import com.fayupable.logged.core.port.MetricsRecorder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * <p>The class and method name reported for each invocation are resolved
 * once per distinct {@code (target class, method)} pair and cached
 * afterward in {@link #methodNameCache}, instead of being recomputed on
 * every single call. {@link Class#getSimpleName()} and
 * {@link Method#getName()} are cheap individually, but on a method called
 * thousands of times per second the repeated string production adds up;
 * caching removes it entirely after the first call. This cache is bounded
 * by the number of distinct {@code @Logged} methods actually invoked in the
 * running application, a quantity fixed by the codebase itself, not by
 * request volume or any user-supplied value, so it cannot grow without
 * bound the way a cache keyed by request data could.
 *
 * <p>On failure, this aspect reports two exception types rather than one:
 * {@link MethodInvocationEvent#exceptionType()}, the type actually thrown
 * across the {@code @Logged} boundary, and
 * {@link MethodInvocationEvent#rootCauseType()}, the type at the end of that
 * exception's {@link Throwable#getCause()} chain. A generic wrapper
 * exception at the boundary (for example a framework's checked-exception
 * translation) would otherwise hide which lower-level failure actually
 * occurred; walking to the root cause recovers that detail while still
 * reporting only class names, never exception messages.
 *
 * <p>A {@code @Logged} method that returns a {@link CompletableFuture} is
 * detected and handled differently from an ordinary synchronous method.
 * {@link ProceedingJoinPoint#proceed()} returning is not the same as the
 * method's work being done: it only means a {@link CompletableFuture} was
 * constructed and handed back, typically before the work it represents has
 * even started on another thread. Recording the invocation at that point
 * would report a near-zero duration and an unconditional success,
 * regardless of what the asynchronous work actually does afterward. Instead,
 * this aspect defers recording until the returned future itself completes,
 * via {@link CompletableFuture#whenComplete}, so the reported duration and
 * outcome reflect the real asynchronous work rather than just the time it
 * took to submit it. The caller identity and call-chain position are still
 * captured synchronously, on the original calling thread, since resolving
 * them later on whatever thread completes the future could observe
 * completely different, thread-bound state (for example an HTTP request
 * that is only available on the original servlet thread).
 *
 * <p>While a {@code @Logged} method runs, this aspect also writes
 * {@link LoggedMdcKeys} into the current thread's {@link org.slf4j.MDC},
 * restoring whatever was there before once the call ends. This is separate
 * from, and in addition to, the summary line {@link #eventEmitter} produces:
 * it lets a consuming application's own log statements made from inside a
 * {@code @Logged} method automatically carry the same trace id, depth,
 * class, and method name, without threading that information through by
 * hand. This can be disabled entirely via {@link #mdcEnabled}, for
 * applications that manage MDC themselves or want to avoid the extra
 * {@code MDC.put}/{@code MDC.remove} calls on a very hot path.
 */
@Aspect
@Component
public class LoggedAspect {

    private static final Logger log = LoggerFactory.getLogger(LoggedAspect.class);
    private static final Runnable NO_OP_MDC_RESTORE = () -> { };

    private final InvocationEventEmitter eventEmitter;
    private final MetricsRecorder metricsRecorder;
    private final IClientInfoPort clientInfoPort;
    private final boolean mdcEnabled;
    private final ConcurrentHashMap<MethodKey, ResolvedNames> methodNameCache = new ConcurrentHashMap<>();

    /**
     * Creates an aspect with MDC propagation enabled, the default for any
     * consumer that does not need to disable it explicitly.
     *
     * @param eventEmitter    collaborator that emits sampled/failed/slow
     *                        invocations
     * @param metricsRecorder collaborator that records every invocation as
     *                        a metric
     * @param clientInfoPort  collaborator that resolves the caller's
     *                        identity
     */
    public LoggedAspect(InvocationEventEmitter eventEmitter, MetricsRecorder metricsRecorder, IClientInfoPort clientInfoPort) {
        this(eventEmitter, metricsRecorder, clientInfoPort, true);
    }

    /**
     * Creates an aspect with explicit control over MDC propagation.
     *
     * @param eventEmitter    collaborator that emits sampled/failed/slow
     *                        invocations
     * @param metricsRecorder collaborator that records every invocation as
     *                        a metric
     * @param clientInfoPort  collaborator that resolves the caller's
     *                        identity
     * @param mdcEnabled      whether {@link LoggedMdcKeys} should be written
     *                        to the current thread's MDC while a
     *                        {@code @Logged} method runs
     */
    public LoggedAspect(InvocationEventEmitter eventEmitter, MetricsRecorder metricsRecorder,
                         IClientInfoPort clientInfoPort, boolean mdcEnabled) {
        this.eventEmitter = eventEmitter;
        this.metricsRecorder = metricsRecorder;
        this.clientInfoPort = clientInfoPort;
        this.mdcEnabled = mdcEnabled;
    }

    /**
     * Intercepts a {@link Logged}-annotated method call, measuring its
     * duration and outcome, and delegating the emission decision and
     * call-chain tracking to their respective collaborators.
     *
     * <p>The captured class name is resolved from {@link ProceedingJoinPoint#getTarget()}
     * rather than {@link ProceedingJoinPoint#getSignature()}, since the
     * latter reports the type on which the intercepted method is declared.
     * When {@code @Logged} is placed on a method declared by an interface
     * (for example {@code UserService}) and implemented by a concrete class
     * (for example {@code UserServiceImpl}), resolving from the signature
     * would report the interface name instead of the class actually
     * handling the call. {@code getTarget()} returns the real object being
     * advised, so the reported class name always matches what is actually
     * running, regardless of whether the annotated method is declared on
     * an interface or directly on the class.
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
        ResolvedNames names = resolveNames(pjp);
        String className = names.className();
        String methodName = names.methodName();
        String callerIdentity = clientInfoPort.resolveCallerIdentity();
        long start = System.nanoTime();

        FlowContextHolder.FlowScope flowScope = FlowContextHolder.enter();
        Runnable restoreMdc = mdcEnabled
                ? MdcPropagation.push(flowScope.context(), className, methodName)
                : NO_OP_MDC_RESTORE;
        try {
            Object result;
            try {
                result = pjp.proceed();
            } catch (Throwable t) {
                String exceptionType = t.getClass().getSimpleName();
                String rootCauseType = resolveRootCauseType(t);
                long durationNanos = System.nanoTime() - start;
                recordObservability(logged, className, methodName, callerIdentity, durationNanos,
                        false, exceptionType, rootCauseType, flowScope.context());
                throw t;
            }

            if (result instanceof CompletableFuture<?> future) {
                FlowContext capturedContext = flowScope.context();
                return instrumentCompletableFuture(future, logged, className, methodName, callerIdentity, start, capturedContext);
            }

            long durationNanos = System.nanoTime() - start;
            recordObservability(logged, className, methodName, callerIdentity, durationNanos,
                    true, null, null, flowScope.context());
            return result;
        } finally {
            // Runs even if recordObservability lets an Error escape (it only
            // catches RuntimeException, deliberately - see its own Javadoc),
            // so this thread's FlowContext/MDC state is never left behind.
            restoreMdc.run();
            flowScope.close();
        }
    }

    /**
     * Attaches a completion callback to {@code future} that records the
     * invocation once the asynchronous work it represents actually
     * finishes, instead of when {@code future} was merely constructed and
     * returned.
     *
     * <p>{@code flowScope} is deliberately not passed here and is closed by
     * the caller before this method attaches anything: {@link
     * FlowContextHolder.FlowScope#close()} restores this call's {@link
     * ThreadLocal}-backed context on the thread that opened it, and the
     * thread that eventually completes {@code future} is frequently a
     * different one. Calling {@code close()} from that other thread would
     * incorrectly overwrite whatever {@link FlowContext} belongs to it. Only
     * {@code capturedContext} — an immutable snapshot of the trace id and
     * depth this call was assigned — is carried into the callback, which is
     * enough to report the correct call-chain position without touching any
     * thread's {@link ThreadLocal} state.
     *
     * @param future          the future returned by the intercepted method
     * @param logged          the {@link Logged} annotation present on the
     *                        intercepted method
     * @param className       the class name resolved for this invocation
     * @param methodName      the method name resolved for this invocation
     * @param callerIdentity  the caller identity resolved synchronously on
     *                        the original calling thread
     * @param start           the {@link System#nanoTime()} reading taken
     *                        when this invocation began
     * @param capturedContext the call-chain position this invocation was
     *                        assigned, captured before the flow scope was
     *                        closed
     * @return {@code future} itself, so callers observe the same
     *         asynchronous result as if this aspect were not present
     */
    private CompletableFuture<?> instrumentCompletableFuture(CompletableFuture<?> future, Logged logged,
                                                              String className, String methodName, String callerIdentity,
                                                              long start, FlowContext capturedContext) {
        return future.whenComplete((value, throwable) -> {
            long durationNanos = System.nanoTime() - start;
            boolean success = throwable == null;
            String exceptionType = null;
            String rootCauseType = null;

            if (throwable != null) {
                Throwable unwrapped = unwrapCompletionException(throwable);
                exceptionType = unwrapped.getClass().getSimpleName();
                rootCauseType = resolveRootCauseType(unwrapped);
            }

            recordObservability(logged, className, methodName, callerIdentity, durationNanos,
                    success, exceptionType, rootCauseType, capturedContext);
        });
    }

    /**
     * Unwraps a {@link CompletionException} to the actual failure it
     * carries as its cause, or returns {@code thrown} unchanged if it is
     * not a {@link CompletionException} or has no cause.
     *
     * <p>{@link CompletableFuture} wraps an exception thrown by a chained
     * stage (for example {@code thenApply}) in a {@link CompletionException}
     * before delivering it to {@link CompletableFuture#whenComplete}. This
     * wrapper is an artifact of how {@link CompletableFuture} propagates
     * failures, not a failure a consuming application's code ever threw;
     * reporting it as {@code exceptionType} would always show the same
     * uninformative wrapper type instead of the actual business exception.
     * Exceptions thrown directly by a future created with
     * {@link CompletableFuture#supplyAsync(java.util.function.Supplier)} are
     * delivered without this wrapper, so this method only unwraps when the
     * wrapper is actually present.
     *
     * @param thrown the throwable delivered to {@link
     *               CompletableFuture#whenComplete}
     * @return the underlying failure, or {@code thrown} itself if there is
     *         no {@link CompletionException} wrapper to remove
     */
    private Throwable unwrapCompletionException(Throwable thrown) {
        return (thrown instanceof CompletionException) && thrown.getCause() != null ? thrown.getCause() : thrown;
    }

    /**
     * Walks {@link Throwable#getCause()} to the deepest cause reachable from
     * {@code thrown} and returns its simple class name.
     *
     * <p>Frameworks routinely wrap a lower-level failure inside a more
     * generic exception type as it propagates up the call stack; a database
     * driver's connection error, for example, may surface at the
     * {@code @Logged} boundary as a generic Spring {@code DataAccessException}
     * with the original exception attached as its cause. Reporting only
     * {@code thrown.getClass()} would hide which failure actually occurred.
     * This method returns the type at the end of the cause chain instead,
     * which is the original failure in the common wrapping case. When
     * {@code thrown} has no cause, it is its own root, so this returns the
     * same simple name already captured as {@code exceptionType}.
     *
     * <p>A cause chain is walked rather than trusted to terminate on its own
     * because a misbehaving {@link Throwable} subclass could return itself
     * (or otherwise form a cycle) from {@link Throwable#getCause()}. Guarding
     * against already-visited instances with an identity set prevents such a
     * cycle from turning this into an infinite loop.
     *
     * @param thrown the exception caught at the {@code @Logged} boundary
     * @return the simple class name of the deepest cause in the chain
     */
    private String resolveRootCauseType(Throwable thrown) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = thrown;
        visited.add(current);

        Throwable cause = current.getCause();
        while (cause != null && visited.add(cause)) {
            current = cause;
            cause = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    /**
     * Records the outcome of a single invocation through {@link
     * #metricsRecorder} and, if {@link EmissionPolicy} selects it, through
     * {@link #eventEmitter}.
     *
     * <p>Takes an already-resolved {@code callerIdentity} and {@link
     * FlowContext} rather than resolving them itself, so this method works
     * identically whether it is called synchronously, right after {@link
     * ProceedingJoinPoint#proceed()} returns, or later from a {@link
     * CompletableFuture} completion callback running on a different thread:
     * neither value depends on {@link ThreadLocal} state at the point this
     * method runs.
     */
    private void recordObservability(Logged logged, String className, String methodName, String callerIdentity,
                                      long durationNanos, boolean success, String exceptionType, String rootCauseType,
                                      FlowContext context) {
        try {
            metricsRecorder.record(className, methodName, durationNanos, success, exceptionType);

            if (EmissionPolicy.shouldEmit(logged, durationNanos, success)) {
                eventEmitter.emit(new MethodInvocationEvent(
                        className, methodName, Instant.now(), durationNanos, success, exceptionType, rootCauseType,
                        callerIdentity, context.traceId(), context.depth()
                ));
            }
        } catch (RuntimeException e) {
            log.warn("Ignoring failure while recording observability data for {}.{}", className, methodName, e);
        }
    }

    /**
     * Resolves the class and method name to report for this join point,
     * consulting {@link #methodNameCache} first and computing and caching
     * them only on the first call for a given {@code (target class,
     * method)} pair.
     *
     * @param pjp the join point representing the intercepted call
     * @return the class and method name to report for this invocation
     */
    private ResolvedNames resolveNames(ProceedingJoinPoint pjp) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        MethodKey key = new MethodKey(pjp.getTarget().getClass(), method);
        return methodNameCache.computeIfAbsent(key,
                k -> new ResolvedNames(k.targetClass().getSimpleName(), k.method().getName()));
    }

    /**
     * Cache key identifying a single {@code (target class, method)} pair.
     *
     * <p>Both components already have well-defined, identity-independent
     * {@code equals}/{@code hashCode} implementations ({@link Class} compares
     * by the class it represents, {@link Method} by its declaring class,
     * name, and parameter types), so this record is safe to use as a
     * {@link ConcurrentHashMap} key without any custom equality logic.
     *
     * @param targetClass the runtime class of the object the intercepted
     *                     method was invoked on, as resolved by
     *                     {@link ProceedingJoinPoint#getTarget()}
     * @param method       the intercepted method itself
     */
    private record MethodKey(Class<?> targetClass, Method method) {
    }

    /**
     * The class and method name resolved for a single {@link MethodKey},
     * cached so that {@link Class#getSimpleName()} and
     * {@link Method#getName()} are each computed only once per distinct
     * intercepted method.
     *
     * @param className  the simple name of the target class
     * @param methodName the name of the intercepted method
     */
    private record ResolvedNames(String className, String methodName) {
    }
}