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
 * ordering in the common case: by the time any {@code @Logged} method is
 * actually invoked, the application context has usually fully started, and
 * any {@link MeterRegistry} bean that is ever going to exist already does.
 *
 * <p>That said, a {@code @Logged} method can still be invoked earlier than
 * that — for example from another bean's {@code @PostConstruct} — before a
 * {@link MeterRegistry} bean has been created. Only the <em>found</em>
 * outcome is cached in {@link #delegate}: if no {@link MeterRegistry} is
 * available yet, this class deliberately does not pin itself to
 * {@link NoOpMetricsRecorder} forever, and instead checks again on every
 * subsequent call until one is found. Caching a negative result here would
 * silently and permanently disable metrics for the rest of the
 * application's lifetime the moment this class lost that one-time race,
 * which is a far worse failure mode than re-checking an already-cheap
 * {@link ObjectProvider#getIfAvailable()} call a few extra times early in
 * the application's startup.
 */
public class LazyMetricsRecorder implements MetricsRecorder {

    private static final MetricsRecorder NO_OP = new NoOpMetricsRecorder();

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
            if (delegate != null) {
                return delegate;
            }
            MeterRegistry registry = meterRegistryProvider.getIfAvailable();
            if (registry == null) {
                return NO_OP;
            }
            delegate = new MicrometerMetricsRecorder(registry);
            return delegate;
        }
    }
}
