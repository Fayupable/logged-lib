package com.fayupable.logged.spring.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet {@link jakarta.servlet.Filter} that reads the incoming HTTP
 * request's trace headers, if present, and adopts them as the active
 * {@code @Logged} call chain for the duration of the request, via
 * {@link HttpTraceHeaderCarrier}.
 *
 * <p>This is the inbound counterpart to
 * {@link HttpTraceClientHttpRequestInterceptor}/{@link
 * FeignTraceRequestInterceptor}/{@link HttpTraceExchangeFilterFunction}: a
 * service that only sends the outbound trace headers but never reads them
 * back on the receiving side would never actually observe the propagated
 * chain continue — this filter is the other half of that exchange.
 *
 * <p>Register as a Spring bean, with high precedence so the adopted context
 * is active for as much of the request-handling chain as possible,
 * including any {@code @Logged} controller/service methods invoked further
 * down the chain:
 *
 * <pre>{@code
 * @Bean
 * public FilterRegistrationBean<HttpTraceServletFilter> httpTraceServletFilter() {
 *     FilterRegistrationBean<HttpTraceServletFilter> registration =
 *             new FilterRegistrationBean<>(new HttpTraceServletFilter());
 *     registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
 *     return registration;
 * }
 * }</pre>
 *
 * <p>Not wired in automatically: a consuming application decides whether it
 * wants incoming requests to participate in cross-service tracing, the same
 * way it decides which methods get {@code @Logged} in the first place.
 */
public final class HttpTraceServletFilter extends OncePerRequestFilter {

    /**
     * Reads the incoming request's trace headers, if present, and runs the
     * rest of the filter chain with them adopted as the active call chain
     * for this thread, restoring this thread's previous context again
     * afterward, regardless of whether the chain completes normally or
     * throws.
     *
     * @param request     the incoming HTTP request
     * @param response    the outgoing HTTP response, passed through
     *                    unchanged
     * @param filterChain the rest of the filter chain
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            HttpTraceHeaderCarrier.readAndAdopt(request::getHeader, () -> {
                try {
                    filterChain.doFilter(request, response);
                } catch (ServletException | IOException e) {
                    throw new FilterChainExecutionException(e);
                }
            });
        } catch (FilterChainExecutionException wrapped) {
            wrapped.rethrow();
        }
    }

    /**
     * Carries a checked {@link ServletException}/{@link IOException} thrown
     * by {@link FilterChain#doFilter} through {@link HttpTraceHeaderCarrier#readAndAdopt},
     * whose {@code work} parameter is a plain {@link Runnable} and cannot
     * declare checked exceptions itself.
     */
    private static final class FilterChainExecutionException extends RuntimeException {
        FilterChainExecutionException(Exception cause) {
            super(cause);
        }

        void rethrow() throws ServletException, IOException {
            Throwable cause = getCause();
            if (cause instanceof ServletException servletException) {
                throw servletException;
            }
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw this;
        }
    }
}
