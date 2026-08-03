package com.fayupable.logged.spring.http;

import org.jspecify.annotations.NonNull;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * {@link ExchangeFilterFunction} that writes the current {@code @Logged}
 * call chain's trace id and depth into an outgoing {@code WebClient}
 * request's headers, via {@link HttpTraceHeaderCarrier}.
 *
 * <p><strong>Thread caveat, unlike {@link HttpTraceClientHttpRequestInterceptor}
 * and {@link FeignTraceRequestInterceptor}:</strong> {@code WebClient} is
 * reactive, and {@link #filter} runs whenever the returned {@link Mono} is
 * actually subscribed to — which, if the request is built with
 * {@code subscribeOn}/{@code publishOn} or otherwise composed across
 * schedulers, may not be the same thread that constructed the
 * {@code WebClient} call in application code. In that case, this filter
 * captures whatever {@link com.fayupable.logged.core.model.FlowContext} is
 * active on the thread that actually triggers the exchange, which is not
 * guaranteed to be the calling thread's. For the common case of a
 * {@code @Logged} method calling {@code WebClient} and blocking on the
 * result (for example via {@code .block()}), the subscribing thread and the
 * calling thread are the same, and this behaves exactly like the
 * {@code RestTemplate} and Feign integrations. Full correctness across an
 * arbitrarily composed reactive chain would require bridging {@code
 * FlowContext} through Reactor's own {@code Context}, which this library
 * does not yet do (see the reactive {@code Publisher} return type
 * limitation documented on {@code LoggedTargetGuardBeanPostProcessor}).
 *
 * <p>Register this on the specific {@code WebClient} instance you want to
 * carry trace context across service calls:
 *
 * <pre>{@code
 * WebClient webClient = WebClient.builder()
 *         .filter(new HttpTraceExchangeFilterFunction())
 *         .build();
 * }</pre>
 *
 * <p>Not wired in automatically: a consuming application decides which
 * {@code WebClient} instances should carry trace context, the same way it
 * decides which methods get {@code @Logged} in the first place.
 */
public final class HttpTraceExchangeFilterFunction implements ExchangeFilterFunction {

    /**
     * Writes the current call chain's trace headers onto a copy of
     * {@code request} before delegating to {@code next}.
     *
     * @param request the outgoing request
     * @param next     delegates the actual exchange
     * @return the response {@link Mono} returned by {@code next}
     */
    @Override
    public @NonNull Mono<ClientResponse> filter(@NonNull ClientRequest request, @NonNull ExchangeFunction next) {
        ClientRequest.Builder requestWithTrace = ClientRequest.from(request);
        HttpTraceHeaderCarrier.writeToHeaders(requestWithTrace::header);
        return next.exchange(requestWithTrace.build());
    }
}
