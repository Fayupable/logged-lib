package com.fayupable.logged.spring.security;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Captures and restores the current thread's Spring Web {@link RequestAttributes}
 * across a thread boundary, mirroring the {@code FlowContextHolder}
 * {@code snapshot()}/{@code adopt()} pair in
 * {@code com.fayupable.logged.spring.aspect} but for Spring's own
 * request-scoped {@link ThreadLocal}, not this library's {@code FlowContext}.
 *
 * <p>{@link RequestContextHolder} stores the current {@link RequestAttributes}
 * per thread. {@link RequestClientIpResolver} (and, through it,
 * {@link HttpRequestClientInfoAdapter}/{@link SpringSecurityClientInfoAdapter})
 * reads from it to resolve a caller's IP address. Like this library's own
 * {@code FlowContext}, this is invisible to any thread other than the one
 * that set it, so a nested {@code @Logged} call made from inside work handed
 * off to another thread cannot resolve a request-derived caller identity
 * unless something first carries {@link RequestAttributes} across that
 * boundary — which is exactly what this class exists to do, for
 * {@link RequestAttributesPropagatingExecutor} and
 * {@link RequestAttributesTaskDecorator}.
 *
 * <p>Package-private: this is an internal detail shared by those two
 * classes, not part of this library's public API.
 */
final class RequestAttributesPropagation {

    private RequestAttributesPropagation() {
    }

    /**
     * Returns the {@link RequestAttributes} currently active on this
     * thread, or {@code null} if there is no active HTTP request on this
     * thread.
     *
     * @return the active request attributes, or {@code null}
     */
    static RequestAttributes capture() {
        return RequestContextHolder.getRequestAttributes();
    }

    /**
     * Makes {@code attributes} the active {@link RequestAttributes} on the
     * calling thread and returns a {@link Runnable} that undoes this,
     * restoring whatever was active before this call.
     *
     * @param attributes the request attributes captured by {@link #capture()}
     *                   on the thread that is handing off work, or
     *                   {@code null} if that thread had none active
     * @return a {@link Runnable} that restores this thread's previous
     *         request attributes; never {@code null}
     */
    static Runnable adopt(RequestAttributes attributes) {
        RequestAttributes previous = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.setRequestAttributes(attributes);
        return () -> RequestContextHolder.setRequestAttributes(previous);
    }
}
