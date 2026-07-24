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
 */
public class MicrometerMetricsRecorder implements MetricsRecorder {

    private static final String INVOCATIONS_METRIC = "method.invocations";
    private static final String DURATION_METRIC = "method.duration";
    private static final String ERRORS_METRIC = "method.errors";

    private static final String SUCCESS_OUTCOME = "success";
    private static final String ERROR_OUTCOME = "error";

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, Counter> invocationCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> durationTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> errorCounters = new ConcurrentHashMap<>();

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
        String key = className + '.' + methodName + '.' + outcome;
        return invocationCounters.computeIfAbsent(key, ignored -> Counter.builder(INVOCATIONS_METRIC)
                .tag("class", className)
                .tag("method", methodName)
                .tag("outcome", outcome)
                .register(meterRegistry));
    }

    private Timer durationTimer(String className, String methodName) {
        String key = className + '.' + methodName;
        return durationTimers.computeIfAbsent(key, ignored -> Timer.builder(DURATION_METRIC)
                .tag("class", className)
                .tag("method", methodName)
                .publishPercentileHistogram()
                .register(meterRegistry));
    }

    private Counter errorCounter(String className, String methodName, String exceptionType) {
        String key = className + '.' + methodName + '.' + exceptionType;
        return errorCounters.computeIfAbsent(key, ignored -> Counter.builder(ERRORS_METRIC)
                .tag("class", className)
                .tag("method", methodName)
                .tag("exception", exceptionType)
                .register(meterRegistry));
    }
}
