package com.fayupable.logged.spring.http;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * {@link ClientHttpRequestInterceptor} that writes the current
 * {@code @Logged} call chain's trace id and depth into an outgoing
 * {@code RestTemplate} request's headers, via {@link HttpTraceHeaderCarrier}.
 *
 * <p>{@code RestTemplate} is purely synchronous/blocking, so — unlike
 * {@link HttpTraceExchangeFilterFunction} for {@code WebClient} — there is
 * no ambiguity about which thread's {@link
 * com.fayupable.logged.core.model.FlowContext} is captured: it is always
 * the thread that made the {@code RestTemplate} call.
 *
 * <p>Register this on the specific {@code RestTemplate} instance you want
 * to carry trace context across service calls:
 *
 * <pre>{@code
 * RestTemplate restTemplate = new RestTemplate();
 * restTemplate.getInterceptors().add(new HttpTraceClientHttpRequestInterceptor());
 * }</pre>
 *
 * <p>Not wired in automatically: a consuming application decides which
 * {@code RestTemplate} instances should carry trace context, the same way
 * it decides which methods get {@code @Logged} in the first place.
 */
public final class HttpTraceClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    /**
     * Writes the current call chain's trace headers onto {@code request}
     * before delegating to {@code execution}.
     *
     * @param request   the outgoing request
     * @param body      the outgoing request body, passed through unchanged
     * @param execution delegates the actual request execution
     * @return the response returned by {@code execution}
     */
    @Override
    public @NonNull ClientHttpResponse intercept(@NonNull HttpRequest request, @NonNull byte[] body,
                                                  @NonNull ClientHttpRequestExecution execution) throws IOException {
        HttpTraceHeaderCarrier.writeToHeaders(request.getHeaders()::set);
        return execution.execute(request, body);
    }
}
