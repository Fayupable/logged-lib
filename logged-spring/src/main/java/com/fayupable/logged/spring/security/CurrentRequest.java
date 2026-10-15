package com.fayupable.logged.spring.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Looks up the HTTP request bound to the current thread, shared by the
 * request-based resolvers in this package so each does not repeat the
 * {@link RequestContextHolder} lookup.
 */
final class CurrentRequest {

    private CurrentRequest() {
    }

    /**
     * @return the current thread's servlet request, or {@code null} when the
     *         thread is not handling one (a scheduler, an async worker
     *         without propagation, a message listener)
     */
    static HttpServletRequest get() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        return attributes.getRequest();
    }
}
