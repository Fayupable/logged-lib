package com.fayupable.logged.spring.metrics;

import com.fayupable.logged.core.port.MetricsRecorder;
import com.fayupable.logged.core.port.NoOpMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link MetricsRecorder} that defers the choice between a real
 * Micrometer-backed recorder and a no-op fallback to the first actual
 * invocation, instead of to Spring Boot auto-configuration ordering.
 *
 * <p>Whether a {@link MeterRegistry} bean exists at a given point during
 * context refresh depends on the order in which Spring Boot processes
 * auto-configurations, an order this module cannot reliably pin itself
 * against across Spring Boot versions. Resolving lazily through
 * {@link ObjectProvider}, on the other hand, is unaffected by that
 * ordering: by the time any {@code @Logged} method is actually invoked,
 * the application context has fully started, and any {@link MeterRegistry}
 * bean that is ever going to exist already does.
 */
public class LazyMetricsRecorder implements MetricsRecorder {

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private volatile MetricsRecorder delegate;

    public LazyMetricsRecorder(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    @Override
    public void record(String className, String methodName, long durationNanos, boolean success, String exceptionType) {
        resolveDelegate().record(className, methodName, durationNanos, success, exceptionType);
    }

    private MetricsRecorder resolveDelegate() {
        MetricsRecorder resolved = delegate;
        if (resolved != null) {
            return resolved;
        }
        synchronized (this) {
            if (delegate == null) {
                MeterRegistry registry = meterRegistryProvider.getIfAvailable();
                delegate = registry != null ? new MicrometerMetricsRecorder(registry) : new NoOpMetricsRecorder();
            }
            return delegate;
        }
    }
}
