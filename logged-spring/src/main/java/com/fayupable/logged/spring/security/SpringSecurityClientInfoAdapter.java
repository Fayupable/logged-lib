package com.fayupable.logged.spring.security;

import com.fayupable.logged.core.annotation.CallerMode;
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
 *
 * <p>How the principal's name is written is controlled by a
 * {@link CallerMode}: a configured application-wide mode, which a single
 * {@code @Logged} method can override through {@code @Logged(caller = ...)}.
 * {@link CallerMode#NAME} writes it as is (the default),
 * {@link CallerMode#MASKED} masks it through {@link EmailMasker} when it is an
 * e-mail address, and {@link CallerMode#CUSTOM} delegates to an
 * application-provided {@link CallerIdentityResolver}. If a method requests
 * {@code CUSTOM} but no resolver is configured, the name is masked rather
 * than written in full, so that a missing bean can never leak personal data.
 */
public class SpringSecurityClientInfoAdapter implements IClientInfoPort {

    private static final String ANONYMOUS_PRINCIPAL = "anonymousUser";
    private static final String UNKNOWN_CALLER = "unknown";
    private static final String USER_PREFIX = "user:";

    private final boolean trustForwardedHeaders;
    private final CallerMode configuredMode;
    private final CallerIdentityResolver callerIdentityResolver;

    public SpringSecurityClientInfoAdapter(boolean trustForwardedHeaders) {
        this(trustForwardedHeaders, CallerMode.NAME, null);
    }

    /**
     * @param trustForwardedHeaders  whether {@code X-Forwarded-For} is trusted
     *                               for the IP fallback
     * @param configuredMode         the application-wide caller mode, used for
     *                               methods that do not override it;
     *                               {@code null} or {@link CallerMode#DEFAULT}
     *                               mean {@link CallerMode#NAME}
     * @param callerIdentityResolver resolver used for {@link CallerMode#CUSTOM},
     *                               may be {@code null}
     */
    public SpringSecurityClientInfoAdapter(boolean trustForwardedHeaders, CallerMode configuredMode,
                                           CallerIdentityResolver callerIdentityResolver) {
        this.trustForwardedHeaders = trustForwardedHeaders;
        this.configuredMode = configuredMode == null || configuredMode == CallerMode.DEFAULT
                ? CallerMode.NAME
                : configuredMode;
        this.callerIdentityResolver = callerIdentityResolver;
    }

    @Override
    public String resolveCallerIdentity() {
        return resolveCallerIdentity(CallerMode.DEFAULT);
    }

    @Override
    public String resolveCallerIdentity(CallerMode mode) {
        CallerMode effectiveMode = mode == null || mode == CallerMode.DEFAULT ? configuredMode : mode;
        String authenticatedUser = resolveAuthenticatedUser(effectiveMode);
        if (authenticatedUser != null) {
            return authenticatedUser;
        }

        String clientIp = RequestClientIpResolver.resolveClientIp(trustForwardedHeaders);
        return clientIp != null ? clientIp : UNKNOWN_CALLER;
    }

    private String resolveAuthenticatedUser(CallerMode mode) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || isAnonymous(authentication)) {
            return null;
        }
        if (mode == CallerMode.CUSTOM && callerIdentityResolver != null) {
            String identity = callerIdentityResolver.resolve(authentication);
            return identity == null || identity.isBlank() ? null : USER_PREFIX + identity;
        }
        String name = authentication.getName();
        return USER_PREFIX + (mode == CallerMode.NAME ? name : EmailMasker.mask(name));
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

    /**
     * Resolves a coarse description of the caller's device from the current
     * request's {@code User-Agent} header, never the raw header itself; see
     * {@link DeviceInfoResolver}.
     */
    @Override
    public String resolveCallerDevice() {
        return RequestDeviceResolver.resolveDevice();
    }
}
