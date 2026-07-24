package com.fayupable.logged.core.port;

/**
 * Output port responsible for resolving who triggered the current method
 * invocation.
 *
 * <p>This interface belongs to the framework-free core module and has no
 * knowledge of how the caller is actually identified. Adapter modules
 * resolve this using framework-specific facilities, for example Spring
 * Security's {@code Authentication} for an authenticated user, or the
 * current HTTP request for a client IP address when no user is
 * authenticated. A no-op implementation is provided in this module so that
 * the core can be used standalone, without any adapter.
 *
 * <p>Implementations must never return anything derived from request
 * headers, cookies, or body content beyond a coarse identifier (a user id
 * or an IP address). This port exists to answer "who called this", not to
 * carry arbitrary request data into logs.
 */
public interface IClientInfoPort {

    /**
     * Resolves an identifier describing who triggered the current
     * invocation.
     *
     * @return a caller identity such as {@code "user:42"} or
     *         {@code "ip:203.0.113.10"}, or an implementation-defined
     *         placeholder such as {@code "unknown"} if no identity can be
     *         resolved
     */
    String resolveCallerIdentity();
}