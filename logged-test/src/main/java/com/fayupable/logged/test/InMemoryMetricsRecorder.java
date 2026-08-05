package com.fayupable.logged.test;

import com.fayupable.logged.core.port.MetricsRecorder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link MetricsRecorder} that records every call it is asked to record
 * in memory as a {@link RecordedMetric}, instead of forwarding it to a real
 * metrics backend such as Micrometer.
 *
 * <p>Unlike {@link InMemoryInvocationEventEmitter}, which mirrors a
 * sampled, threshold-gated emission, this recorder always receives every
 * invocation, matching {@link MetricsRecorder}'s own contract; a test
 * asserting on metrics does not need to also configure sampling.
 *
 * <p>Backed by a {@link CopyOnWriteArrayList} for the same reason as
 * {@link InMemoryInvocationEventEmitter}: recording may happen from more
 * than one thread when the method under test hands work off to an
 * executor.
 */
public final class InMemoryMetricsRecorder implements MetricsRecorder {

    private final List<RecordedMetric> metrics = new CopyOnWriteArrayList<>();

    @Override
    public void record(String className, String methodName, long durationNanos, boolean success, String exceptionType) {
        metrics.add(new RecordedMetric(className, methodName, durationNanos, success, exceptionType));
    }

    /**
     * Returns every metric recorded so far, in recording order.
     *
     * @return an immutable snapshot of the recorded metrics
     */
    public List<RecordedMetric> metrics() {
        return List.copyOf(metrics);
    }

    /**
     * Returns the number of metrics recorded so far.
     *
     * @return the recorded metric count
     */
    public int count() {
        return metrics.size();
    }

    /**
     * Returns the most recently recorded metric.
     *
     * @return the last recorded metric
     * @throws IllegalStateException if no metric has been recorded yet
     */
    public RecordedMetric lastMetric() {
        if (metrics.isEmpty()) {
            throw new IllegalStateException("No metric has been recorded yet.");
        }
        return metrics.getLast();
    }

    /**
     * Discards every metric recorded so far, so this instance can be reused
     * across test methods without leaking state between them.
     */
    public void clear() {
        metrics.clear();
    }
}
