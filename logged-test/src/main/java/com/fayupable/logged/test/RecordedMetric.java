package com.fayupable.logged.test;

/**
 * A single call recorded by {@link InMemoryMetricsRecorder}, mirroring the
 * parameters of {@link com.fayupable.logged.core.port.MetricsRecorder#record}
 * so a test can assert on exactly what was reported.
 *
 * @param className     the simple or fully qualified name of the class
 *                       declaring the invoked method
 * @param methodName    the name of the invoked method
 * @param durationNanos the wall-clock duration of the invocation, in
 *                       nanoseconds
 * @param success       {@code true} if the method returned normally,
 *                       {@code false} if it threw an exception
 * @param exceptionType the simple class name of the exception thrown by the
 *                       method, or {@code null} if the call succeeded
 */
public record RecordedMetric(
        String className,
        String methodName,
        long durationNanos,
        boolean success,
        String exceptionType
) {
}
