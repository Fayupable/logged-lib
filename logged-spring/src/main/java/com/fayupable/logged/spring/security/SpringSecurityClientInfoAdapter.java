package com.fayupable.logged.spring.security;

import com.fayupable.logged.core.port.IClientInfoPort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link IClientInfoPort} implementation that resolves the caller as the
 * authenticated Spring Security principal when one is present, falling back
 * to {@link RequestClientIpResolver} otherwise.
 *
 * <p>Only the principal's name is captured, never any other detail carried
 * by the {@link Authentication} (authorities, credentials, session
 * attributes), consistent with the rest of this library's approach of
 * capturing coarse identifiers rather than arbitrary request or security
 * data.
 *
 * <p>This adapter is only active when both Spring Security and Spring Web
 * are present, since it needs {@link Authentication}. Services that do not
 * carry Spring Security at all (for example internal microservices reached
 * only through a gateway that already authenticates the caller) still get
 * IP-based identity through {@link HttpRequestClientInfoAdapter} instead;
 * see that class for details, since IP resolution itself has nothing to do
 * with whether Spring Security is present.
 */
public class SpringSecurityClientInfoAdapter implements IClientInfoPort {

    private static final String ANONYMOUS_PRINCIPAL = "anonymousUser";
    private static final String UNKNOWN_CALLER = "unknown";

    private final boolean trustForwardedHeaders;

    public SpringSecurityClientInfoAdapter(boolean trustForwardedHeaders) {
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    @Override
    public String resolveCallerIdentity() {
        String authenticatedUser = resolveAuthenticatedUser();
        if (authenticatedUser != null) {
            return authenticatedUser;
        }

        String clientIp = RequestClientIpResolver.resolveClientIp(trustForwardedHeaders);
        return clientIp != null ? clientIp : UNKNOWN_CALLER;
    }

    private String resolveAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || isAnonymous(authentication)) {
            return null;
        }
        return "user:" + authentication.getName();
    }

    private boolean isAnonymous(Authentication authentication) {
        return ANONYMOUS_PRINCIPAL.equals(authentication.getPrincipal());
    }

    /**
     * Resolves the caller's IP address independent of {@link #resolveCallerIdentity()}'s
     * outcome, unlike that method's authenticated-principal-first fallback
     * chain. This has no dependency on {@link SecurityContextHolder} state,
     * so it resolves the same way whether or not the current invocation
     * also resolves to an authenticated user.
     */
    @Override
    public String resolveCallerIp() {
        return RequestClientIpResolver.resolveIp(trustForwardedHeaders);
    }
}
