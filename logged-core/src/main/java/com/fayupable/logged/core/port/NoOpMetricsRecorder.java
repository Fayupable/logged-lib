package com.fayupable.logged.core.port;

/**
 * A no-op {@link MetricsRecorder} that discards every invocation it is
 * asked to record.
 *
 * <p>This implementation exists so that the core module remains fully
 * usable on its own, without requiring any metrics backend (for example
 * Micrometer through logged-spring) on the classpath. Adapter modules are
 * expected to register their own {@link MetricsRecorder} implementation and
 * override this default; this class is the fallback when none is provided.
 */
public final class NoOpMetricsRecorder implements MetricsRecorder {

    @Override
    public void record(String className, String methodName, long durationNanos, boolean success, String exceptionType) {
        // Intentionally does nothing: no metrics backend is configured.
    }
}