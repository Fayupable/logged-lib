package com.fayupable.logged.spring.http;

import com.fayupable.logged.core.model.FlowContext;
import com.fayupable.logged.spring.aspect.FlowContextCarrier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HttpTraceClientHttpRequestInterceptor")
class HttpTraceClientHttpRequestInterceptorTest {

    private final HttpTraceClientHttpRequestInterceptor interceptor = new HttpTraceClientHttpRequestInterceptor();

    private final ClientHttpRequestExecution passthroughExecution = new ClientHttpRequestExecution() {
        @Override
        public ClientHttpResponse execute(HttpRequest request, byte[] body) {
            return new MockClientHttpResponse(new byte[0], 200);
        }
    };

    private HttpRequest newRequest() {
        return new MockClientHttpRequest(HttpMethod.GET, URI.create("https://example.com/orders"));
    }

    @Nested
    @DisplayName("intercept()")
    class Intercept {

        @Test
        @DisplayName("writes the active trace id and depth onto the outgoing request's headers")
        void writesActiveContextOntoRequest() throws Exception {
            HttpRequest request = newRequest();
            FlowContext active = new FlowContext("outer-trace", 2);
            Runnable restore = FlowContextCarrier.adopt(active);
            try {
                interceptor.intercept(request, new byte[0], passthroughExecution);

                HttpHeaders headers = request.getHeaders();
                assertThat(headers.getFirst("X-Logged-Trace-Id")).isEqualTo("outer-trace");
                assertThat(headers.getFirst("X-Logged-Depth")).isEqualTo("2");
            } finally {
                restore.run();
            }
        }

        @Test
        @DisplayName("writes no trace headers when no call chain is active on this thread")
        void writesNothingWhenNoContextActive() throws Exception {
            HttpRequest request = newRequest();

            interceptor.intercept(request, new byte[0], passthroughExecution);

            assertThat(request.getHeaders().getFirst("X-Logged-Trace-Id")).isNull();
        }

        @Test
        @DisplayName("still delegates to the execution and returns its response")
        void delegatesToExecution() throws Exception {
            HttpRequest request = newRequest();
            AtomicReference<Boolean> executed = new AtomicReference<>(false);
            ClientHttpRequestExecution execution = (req, body) -> {
                executed.set(true);
                return new MockClientHttpResponse(new byte[0], 200);
            };

            ClientHttpResponse response = interceptor.intercept(request, new byte[0], execution);

            assertThat(executed.get()).isTrue();
            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }
    }
}
