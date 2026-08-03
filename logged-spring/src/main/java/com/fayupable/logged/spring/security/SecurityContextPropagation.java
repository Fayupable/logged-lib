package com.fayupable.logged.spring.security;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Captures and restores the current thread's Spring Security
 * {@link SecurityContext} across a thread boundary, mirroring the
 * {@code FlowContextHolder} {@code snapshot()}/{@code adopt()} pair in
 * {@code com.fayupable.logged.spring.aspect} but for Spring Security's own
 * {@link ThreadLocal}, not this library's {@code FlowContext}.
 *
 * <p>{@link SecurityContextHolder} defaults to
 * {@code MODE_THREADLOCAL}, storing the current {@link SecurityContext} per
 * thread. {@link SpringSecurityClientInfoAdapter} reads from it to resolve
 * the authenticated caller's name. Like this library's own
 * {@code FlowContext}, this is invisible to any thread other than the one
 * that set it, so a nested {@code @Logged} call made from inside work handed
 * off to another thread cannot resolve the authenticated user at all — it
 * silently falls back to an IP address or {@code "unknown"} — unless
 * something first carries {@link SecurityContext} across that boundary,
 * which is exactly what this class exists to do, for
 * {@link SecurityContextPropagatingExecutor} and
 * {@link SecurityContextTaskDecorator}.
 *
 * <p>Package-private: this is an internal detail shared by those two
 * classes, not part of this library's public API.
 */
final class SecurityContextPropagation {

    private SecurityContextPropagation() {
    }

    /**
     * Returns the {@link SecurityContext} currently active on this thread.
     *
     * <p>Unlike {@code FlowContextHolder.snapshot()} and
     * {@code RequestAttributesPropagation.capture()}, this never returns
     * {@code null}: {@link SecurityContextHolder#getContext()} itself lazily
     * creates and stores an empty {@link SecurityContext} the first time it
     * is called on a thread that has none, and returns that instead.
     *
     * @return the active security context; never {@code null}
     */
    static SecurityContext capture() {
        return SecurityContextHolder.getContext();
    }

    /**
     * Makes {@code context} the active {@link SecurityContext} on the
     * calling thread and returns a {@link Runnable} that undoes this,
     * restoring whatever context was active before this call.
     *
     * @param context the security context captured by {@link #capture()} on
     *                the thread that is handing off work; if {@code null}
     *                is passed anyway, this thread's context is cleared
     *                rather than set to {@code null}, since
     *                {@link SecurityContextHolder} does not accept a
     *                {@code null} context
     * @return a {@link Runnable} that restores this thread's previous
     *         security context; never {@code null}
     */
    static Runnable adopt(SecurityContext context) {
        SecurityContext previous = SecurityContextHolder.getContext();
        if (context == null) {
            SecurityContextHolder.clearContext();
        } else {
            SecurityContextHolder.setContext(context);
        }
        return () -> SecurityContextHolder.setContext(previous);
    }
}
