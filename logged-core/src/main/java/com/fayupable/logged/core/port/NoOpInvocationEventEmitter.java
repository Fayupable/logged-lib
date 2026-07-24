package com.fayupable.logged.core.port;

import com.fayupable.logged.core.model.MethodInvocationEvent;

/**
 * A no-op {@link InvocationEventEmitter} that discards every event it
 * receives.
 *
 * <p>This implementation exists so that the core module remains fully
 * usable on its own, without requiring any adapter (for example
 * logged-spring) on the classpath. Adapter modules are expected to
 * register their own {@link InvocationEventEmitter} implementation and
 * override this default; this class is the fallback when none is provided.
 */
public final class NoOpInvocationEventEmitter implements InvocationEventEmitter {

    @Override
    public void emit(MethodInvocationEvent event) {
        // Intentionally does nothing: no sink is configured.
    }
}