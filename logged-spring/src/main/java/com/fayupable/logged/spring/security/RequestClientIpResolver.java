package com.fayupable.logged.spring.security;

import jakarta.servlet.http.HttpServletRequest;

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

    /** Length of the longest textual IPv6 address, an IPv4-mapped one. */
    private static final int MAX_IP_LENGTH = 45;

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
        HttpServletRequest request = CurrentRequest.get();
        if (request == null) {
            return null;
        }

        if (trustForwardedHeaders) {
            String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                String forwardedIp = forwardedFor.split(",")[0].trim();
                if (looksLikeIpAddress(forwardedIp)) {
                    return forwardedIp;
                }
            }
        }

        return request.getRemoteAddr();
    }

    /**
     * Whether {@code value} has the shape of an IPv4 or IPv6 address: only
     * hex digits, dots and colons, at most {@value #MAX_IP_LENGTH}
     * characters. Anything else taken from {@code X-Forwarded-For} (text, a
     * line break, a port, {@code "unknown"}) is ignored in favor of
     * {@link HttpServletRequest#getRemoteAddr()}, so a forged header can
     * never write arbitrary text into a log line. This is a shape check, not
     * full validation, on purpose: {@link java.net.InetAddress} parsing may
     * fall back to a DNS lookup, which has no place on a request path.
     */
    private static boolean looksLikeIpAddress(String value) {
        if (value.isEmpty() || value.length() > MAX_IP_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                    || c == '.' || c == ':';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
