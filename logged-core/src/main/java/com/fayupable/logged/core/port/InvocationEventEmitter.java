package com.fayupable.logged.core.port;

import com.fayupable.logged.core.model.MethodInvocationEvent;

/**
 * Output port responsible for delivering a {@link MethodInvocationEvent} to
 * wherever it needs to go: an SLF4J logger, a metrics backend, a message
 * queue, or any other sink.
 *
 * <p>This interface belongs to the framework-free core module and has no
 * knowledge of how events are actually published. Adapter modules (for
 * example logged-spring) provide the real implementation. A no-op
 * implementation is provided in this module so that the core can be used
 * standalone, without any adapter, without throwing an exception.
 */
public interface InvocationEventEmitter {

    /**
     * Publishes the given invocation event to whatever sink this
     * implementation targets.
     *
     * <p>Implementations must not throw checked or unchecked exceptions
     * that would propagate back into the intercepted method call. Emission
     * failures (for example a logging backend being unavailable) should be
     * handled internally by the implementation.
     *
     * @param event the invocation event to publish, never {@code null}
     */
    void emit(MethodInvocationEvent event);
}