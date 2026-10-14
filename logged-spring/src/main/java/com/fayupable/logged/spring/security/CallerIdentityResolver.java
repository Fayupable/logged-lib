package com.fayupable.logged.spring.security;

import org.springframework.security.core.Authentication;

/**
 * Application-provided strategy that turns an authenticated principal into
 * the identifier written as the caller of a {@code @Logged} invocation, used
 * when the caller mode is {@link com.fayupable.logged.core.annotation.CallerMode#CUSTOM}.
 *
 * <p>Typical use is to log an internal user id instead of a login name that
 * is personal data (such as an e-mail address):
 * <pre>{@code
 * @Bean
 * CallerIdentityResolver callerIdentityResolver() {
 *     return authentication -> authentication.getPrincipal() instanceof AppUser user
 *             ? String.valueOf(user.getId())
 *             : null;
 * }
 * }</pre>
 *
 * <p>The returned value is prefixed with {@code "user:"} by
 * {@link SpringSecurityClientInfoAdapter}. Returning {@code null} or a blank
 * string means "no identity for this principal", in which case the adapter
 * falls back to the caller's IP address exactly as it does for an
 * unauthenticated caller.
 */
@FunctionalInterface
public interface CallerIdentityResolver {

    /**
     * Resolves the identifier for an authenticated, non-anonymous principal.
     *
     * @param authentication the current authentication, never {@code null}
     * @return the caller identifier without any prefix, or {@code null} if
     *         none can be resolved
     */
    String resolve(Authentication authentication);
}
