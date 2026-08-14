package com.fayupable.logged.spring.security;

import com.fayupable.logged.core.port.IClientInfoPort;

/**
 * {@link IClientInfoPort} implementation that resolves the caller purely
 * from the current HTTP request's client IP address, via
 * {@link RequestClientIpResolver}.
 *
 * <p>Resolving a caller's IP address has nothing to do with Spring
 * Security. This adapter exists for applications that carry Spring Web but
 * not Spring Security at all — for example, an internal microservice
 * behind a gateway that already authenticates the caller upstream. Such a
 * service still benefits from knowing which address a call came from, even
 * though it never performs authentication itself, so it should not be
 * forced to fall back to {@code "unknown"} just because Spring Security is
 * absent from its classpath.
 *
 * <p>{@link com.fayupable.logged.spring.config.LoggedAutoConfiguration}
 * only activates this adapter when {@link SpringSecurityClientInfoAdapter}
 * itself is not active (Spring Security missing), so an application with
 * Spring Security still gets the richer, authentication-aware resolution.
 */
public class HttpRequestClientInfoAdapter implements IClientInfoPort {

    private static final String UNKNOWN_CALLER = "unknown";

    private final boolean trustForwardedHeaders;

    public HttpRequestClientInfoAdapter(boolean trustForwardedHeaders) {
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    @Override
    public String resolveCallerIdentity() {
        String clientIp = RequestClientIpResolver.resolveClientIp(trustForwardedHeaders);
        return clientIp != null ? clientIp : UNKNOWN_CALLER;
    }

    @Override
    public String resolveCallerIp() {
        return RequestClientIpResolver.resolveIp(trustForwardedHeaders);
    }
}
