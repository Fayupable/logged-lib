package com.fayupable.logged.spring.http;

import com.fayupable.logged.core.model.FlowContext;
import com.fayupable.logged.spring.aspect.FlowContextCarrier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("HttpTraceExchangeFilterFunction")
class HttpTraceExchangeFilterFunctionTest {

    private final HttpTraceExchangeFilterFunction filterFunction = new HttpTraceExchangeFilterFunction();

    private ClientRequest newRequest() {
        return ClientRequest.create(HttpMethod.GET, URI.create("https://example.com/orders")).build();
    }

    @Nested
    @DisplayName("filter()")
    class Filter {

        @Test
        @DisplayName("writes the active trace id and depth onto the outgoing request's headers")
        void writesActiveContextOntoRequest() {
            AtomicReference<ClientRequest> observedRequest = new AtomicReference<>();
            FlowContext active = new FlowContext("outer-trace", 2);
            Runnable restore = FlowContextCarrier.adopt(active);
            try {
                filterFunction.filter(newRequest(), request -> {
                    observedRequest.set(request);
                    return Mono.just(mock(ClientResponse.class));
                }).block();

                assertThat(observedRequest.get().headers().getFirst("X-Logged-Trace-Id")).isEqualTo("outer-trace");
                assertThat(observedRequest.get().headers().getFirst("X-Logged-Depth")).isEqualTo("2");
            } finally {
                restore.run();
            }
        }

        @Test
        @DisplayName("writes no trace headers when no call chain is active on this thread")
        void writesNothingWhenNoContextActive() {
            AtomicReference<ClientRequest> observedRequest = new AtomicReference<>();

            filterFunction.filter(newRequest(), request -> {
                observedRequest.set(request);
                return Mono.just(mock(ClientResponse.class));
            }).block();

            assertThat(observedRequest.get().headers().getFirst("X-Logged-Trace-Id")).isNull();
        }
    }
}
