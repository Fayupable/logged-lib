package com.fayupable.logged.spring.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the current HTTP request's client IP address, shared by every
 * {@link com.fayupable.logged.core.port.IClientInfoPort} implementation in
 * this module that needs it. This has no dependency on Spring Security:
 * resolving a caller's IP address is a Spring Web concern, not a Spring
 * Security concern, so services that never carry Spring Security on their
 * classpath (for example internal microservices reachable only through a
 * gateway) still get real IP-based caller identity instead of falling back
 * to {@code "unknown"}.
 *
 * <p>By default, the IP is read from {@link HttpServletRequest#getRemoteAddr()}
 * rather than from a header such as {@code X-Forwarded-For}. That header is
 * controlled by the caller and can be forged by anyone unless the
 * application sits behind a proxy configured to strip and re-set it, a
 * deployment detail this library has no way to know. Pass
 * {@code trustForwardedHeaders = true} only after confirming such a proxy
 * is actually in place.
 */
final class RequestClientIpResolver {

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private RequestClientIpResolver() {
    }

    /**
     * Resolves the current request's client IP address, prefixed for use as
     * a {@link com.fayupable.logged.core.port.IClientInfoPort#resolveCallerIdentity()}
     * fallback tier.
     *
     * @param trustForwardedHeaders whether the {@code X-Forwarded-For}
     *                              header may be trusted over
     *                              {@link HttpServletRequest#getRemoteAddr()}
     * @return the caller's IP address prefixed with {@code "ip:"}, or
     *         {@code null} if there is no active HTTP request on this
     *         thread (for example, a scheduled job)
     */
    static String resolveClientIp(boolean trustForwardedHeaders) {
        String ip = resolveIp(trustForwardedHeaders);
        return ip != null ? "ip:" + ip : null;
    }

    /**
     * Resolves the current request's client IP address, unprefixed, for use
     * as a {@link com.fayupable.logged.core.port.IClientInfoPort#resolveCallerIp()}
     * dedicated field rather than as part of the caller-identity string.
     *
     * @param trustForwardedHeaders whether the {@code X-Forwarded-For}
     *                              header may be trusted over
     *                              {@link HttpServletRequest#getRemoteAddr()}
     * @return the caller's raw IP address, or {@code null} if there is no
     *         active HTTP request on this thread (for example, a scheduled
     *         job)
     */
    static String resolveIp(boolean trustForwardedHeaders) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }

        if (trustForwardedHeaders) {
            String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
        }

        return request.getRemoteAddr();
    }

    private static HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        return attributes.getRequest();
    }
}
