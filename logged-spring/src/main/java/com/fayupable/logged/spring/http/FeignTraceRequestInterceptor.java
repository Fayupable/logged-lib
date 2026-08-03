package com.fayupable.logged.spring.http;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Feign {@link RequestInterceptor} that writes the current {@code @Logged}
 * call chain's trace id and depth into an outgoing Feign client request's
 * headers, via {@link HttpTraceHeaderCarrier}.
 *
 * <p>Feign clients are purely synchronous/blocking, exactly like
 * {@code RestTemplate}: there is no ambiguity about which thread's {@link
 * com.fayupable.logged.core.model.FlowContext} is captured, since it is
 * always the thread that made the Feign call.
 *
 * <p>Register this as a Spring bean; Spring Cloud OpenFeign auto-detects any
 * {@link RequestInterceptor} bean and applies it to every Feign client in
 * the application:
 *
 * <pre>{@code
 * @Bean
 * public RequestInterceptor loggedTraceRequestInterceptor() {
 *     return new FeignTraceRequestInterceptor();
 * }
 * }</pre>
 *
 * <p>Not wired in automatically by this library itself — declaring the bean
 * above is the explicit, one-time opt-in step, the same as every other
 * propagation class in this library.
 */
public final class FeignTraceRequestInterceptor implements RequestInterceptor {

    /**
     * Writes the current call chain's trace headers onto {@code template}.
     *
     * @param template the outgoing Feign request template
     */
    @Override
    public void apply(RequestTemplate template) {
        HttpTraceHeaderCarrier.writeToHeaders(template::header);
    }
}
