package com.fayupable.logged.core.port;

/**
 * A no-op {@link IClientInfoPort} that always reports the caller as
 * unknown.
 *
 * <p>This implementation exists so that the core module remains fully
 * usable on its own, without requiring any adapter (for example
 * logged-spring, which resolves the caller through Spring Security or the
 * current HTTP request) on the classpath. Adapter modules are expected to
 * register their own {@link IClientInfoPort} implementation and override
 * this default; this class is the fallback when none is provided.
 */
public final class NoOpClientInfoPort implements IClientInfoPort {

    private static final String UNKNOWN_CALLER = "unknown";

    @Override
    public String resolveCallerIdentity() {
        return UNKNOWN_CALLER;
    }
}