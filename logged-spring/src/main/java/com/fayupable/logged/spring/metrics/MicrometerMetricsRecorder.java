package com.fayupable.logged.spring.metrics;

import com.fayupable.logged.core.port.MetricsRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * {@link MetricsRecorder} implementation backed by Micrometer.
 *
 * <p>Every invocation is recorded against three meters:
 * <ul>
 *     <li>{@code method.invocations}: a counter tagged with {@code class},
 *     {@code method}, and {@code outcome} ({@code success} or
 *     {@code error}), tracking how many times a method was called.</li>
 *     <li>{@code method.duration}: a timer tagged with {@code class} and
 *     {@code method}, tracking how long calls took, with a percentile
 *     histogram published for latency dashboards.</li>
 *     <li>{@code method.errors}: a counter tagged with {@code class},
 *     {@code method}, and {@code exception}, registered only when an
 *     invocation fails, tracking which exceptions occur and how often.</li>
 * </ul>
 *
 * <p>All tags are bounded to a small, fixed set of low-cardinality values
 * derived from the invoked method itself. Method arguments and return
 * values are never used as tags, since they could otherwise produce an
 * unbounded number of time series and degrade the metrics backend.
 *
 * <p>Meters are looked up once per distinct tag combination and cached
 * afterward. {@link MeterRegistry}'s own {@code register} call is not
 * prohibitively expensive, but it still allocates a {@code Meter.Id} and
 * performs a map lookup on every call; caching avoids repeating that work
 * on every single invocation of a hot {@code @Logged} method.
 *
 * <p>Cache keys are dedicated records ({@link InvocationCounterKey},
 * {@link DurationTimerKey}, {@link ErrorCounterKey}) rather than
 * concatenated strings. {@code className} may be a fully qualified name
 * (per {@link MetricsRecorder#record}'s contract), which can itself contain
 * {@code '.'} characters; a string key built by joining fields with a
 * separator could then theoretically collide between two different
 * {@code (class, method, outcome)} triples. A record's {@code equals()}
 * compares each field independently, so no such ambiguity is possible.
 */
public class MicrometerMetricsRecorder implements MetricsRecorder {

    private static final String INVOCATIONS_METRIC = "method.invocations";
    private static final String DURATION_METRIC = "method.duration";
    private static final String ERRORS_METRIC = "method.errors";

    private static final String SUCCESS_OUTCOME = "success";
    private static final String ERROR_OUTCOME = "error";

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<InvocationCounterKey, Counter> invocationCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<DurationTimerKey, Timer> durationTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ErrorCounterKey, Counter> errorCounters = new ConcurrentHashMap<>();

    public MicrometerMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records the given invocation against the invocation counter, the
     * duration timer, and, if the call failed, the error counter.
     *
     * @param className     the simple or fully qualified name of the class
     *                       declaring the invoked method
     * @param methodName    the name of the invoked method
     * @param durationNanos the wall-clock duration of the invocation, in
     *                      nanoseconds
     * @param success       {@code true} if the method returned normally,
     *                      {@code false} if it threw an exception
     * @param exceptionType the simple class name of the exception thrown by
     *                      the method, or {@code null} if the call
     *                      succeeded
     */
    @Override
    public void record(String className, String methodName, long durationNanos, boolean success, String exceptionType) {
        String outcome = success ? SUCCESS_OUTCOME : ERROR_OUTCOME;

        invocationCounter(className, methodName, outcome).increment();
        durationTimer(className, methodName).record(durationNanos, TimeUnit.NANOSECONDS);

        if (!success) {
            errorCounter(className, methodName, exceptionType).increment();
        }
    }

    private Counter invocationCounter(String className, String methodName, String outcome) {
        InvocationCounterKey key = new InvocationCounterKey(className, methodName, outcome);
        return invocationCounters.computeIfAbsent(key, k -> Counter.builder(INVOCATIONS_METRIC)
                .tag("class", k.className())
                .tag("method", k.methodName())
                .tag("outcome", k.outcome())
                .register(meterRegistry));
    }

    private Timer durationTimer(String className, String methodName) {
        DurationTimerKey key = new DurationTimerKey(className, methodName);
        return durationTimers.computeIfAbsent(key, k -> Timer.builder(DURATION_METRIC)
                .tag("class", k.className())
                .tag("method", k.methodName())
                .publishPercentileHistogram()
                .register(meterRegistry));
    }

    private Counter errorCounter(String className, String methodName, String exceptionType) {
        ErrorCounterKey key = new ErrorCounterKey(className, methodName, exceptionType);
        return errorCounters.computeIfAbsent(key, k -> Counter.builder(ERRORS_METRIC)
                .tag("class", k.className())
                .tag("method", k.methodName())
                .tag("exception", k.exceptionType())
                .register(meterRegistry));
    }

    /**
     * Identifies a single {@code method.invocations} counter by class,
     * method, and outcome.
     */
    private record InvocationCounterKey(String className, String methodName, String outcome) {
    }

    /**
     * Identifies a single {@code method.duration} timer by class and
     * method.
     */
    private record DurationTimerKey(String className, String methodName) {
    }

    /**
     * Identifies a single {@code method.errors} counter by class, method,
     * and exception type.
     */
    private record ErrorCounterKey(String className, String methodName, String exceptionType) {
    }
}
