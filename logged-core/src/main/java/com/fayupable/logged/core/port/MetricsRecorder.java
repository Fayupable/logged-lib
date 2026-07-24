package com.fayupable.logged.core.port;

/**
 * Output port responsible for recording invocation metrics: how many times
 * a method was called, how long calls took, and how many of them failed.
 *
 * <p>Unlike {@link InvocationEventEmitter}, which is subject to sampling and
 * threshold rules, implementations of this port are expected to record
 * every single invocation, since metrics back dashboards and alerts that
 * depend on complete data rather than a sampled view.
 *
 * <p>This interface belongs to the framework-free core module and has no
 * knowledge of any specific metrics backend (for example Micrometer).
 * Adapter modules provide the real implementation. A no-op implementation
 * is provided in this module so that the core can be used standalone,
 * without any metrics backend, without throwing an exception.
 */
public interface MetricsRecorder {

    /**
     * Records the outcome and duration of a single method invocation.
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
    void record(String className, String methodName, long durationNanos, boolean success, String exceptionType);
}