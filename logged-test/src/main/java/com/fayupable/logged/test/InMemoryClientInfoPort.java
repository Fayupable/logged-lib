package com.fayupable.logged.test;

import com.fayupable.logged.core.port.IClientInfoPort;

/**
 * An {@link IClientInfoPort} that always returns a fixed, test-controlled
 * caller identity, instead of resolving one from Spring Security or the
 * current HTTP request.
 *
 * <p>A test asserting on {@link com.fayupable.logged.core.model.MethodInvocationEvent#callerIdentity()}
 * would otherwise need a real authenticated {@code SecurityContext} or a
 * mock HTTP request just to control this one field. This class lets the
 * caller identity be set directly instead.
 */
public final class InMemoryClientInfoPort implements IClientInfoPort {

    private static final String DEFAULT_CALLER_IDENTITY = "unknown";

    private volatile String callerIdentity;
    private volatile String callerIp;

    /**
     * Creates an instance that reports the caller identity as
     * {@code "unknown"} until {@link #setCallerIdentity(String)} is called.
     */
    public InMemoryClientInfoPort() {
        this(DEFAULT_CALLER_IDENTITY);
    }

    /**
     * Creates an instance that reports the given caller identity.
     *
     * @param callerIdentity the caller identity to report
     */
    public InMemoryClientInfoPort(String callerIdentity) {
        this.callerIdentity = callerIdentity;
    }

    @Override
    public String resolveCallerIdentity() {
        return callerIdentity;
    }

    /**
     * Changes the caller identity reported by subsequent calls to
     * {@link #resolveCallerIdentity()}.
     *
     * @param callerIdentity the caller identity to report from now on
     */
    public void setCallerIdentity(String callerIdentity) {
        this.callerIdentity = callerIdentity;
    }

    @Override
    public String resolveCallerIp() {
        return callerIp;
    }

    /**
     * Changes the IP address reported by subsequent calls to
     * {@link #resolveCallerIp()}, for testing a {@code @Logged(includeIp = true)}
     * method's {@link com.fayupable.logged.core.model.MethodInvocationEvent#callerIp()}
     * without wiring a real HTTP request. Defaults to {@code null}
     * (not resolved) until this is called.
     *
     * @param callerIp the IP address to report from now on
     */
    public void setCallerIp(String callerIp) {
        this.callerIp = callerIp;
    }
}
