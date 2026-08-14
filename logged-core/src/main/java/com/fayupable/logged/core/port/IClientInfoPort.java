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

    /**
     * Resolves the IP address of the caller that triggered the current
     * invocation, independent of whatever {@link #resolveCallerIdentity()}
     * itself resolves to.
     *
     * <p>Unlike {@link #resolveCallerIdentity()}, which resolves a single
     * identity from an either/or chain of tiers (authenticated principal,
     * then IP, then a placeholder), this method exists so that a caller's IP
     * can be recorded alongside an authenticated identity rather than only
     * as a fallback used when no identity is available — valuable for
     * security-sensitive operations (login, password reset, admin actions)
     * where the IP remains useful for audit and rate-limiting purposes even
     * when the call also resolves to an authenticated user.
     *
     * <p>This is a default method, not an abstract one, so that adding it
     * does not break existing implementations of this interface compiled
     * against an earlier version of this library. The default returns
     * {@code null}, meaning "this adapter does not support resolving an IP
     * independent of caller identity" — consistent with this library's
     * convention of using {@code null} for "not available" on structured
     * fields, rather than a placeholder string.
     *
     * @return the caller's IP address, or {@code null} if it cannot be
     *         resolved (for example, no HTTP request is available on the
     *         current thread, or this adapter does not implement IP
     *         resolution)
     */
    default String resolveCallerIp() {
        return null;
    }
}