package com.fayupable.logged.spring.http;

import com.fayupable.logged.core.model.FlowContext;
import com.fayupable.logged.spring.aspect.FlowContextCarrier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HttpTraceServletFilter")
class HttpTraceServletFilterTest {

    private final HttpTraceServletFilter filter = new HttpTraceServletFilter();

    @Nested
    @DisplayName("doFilterInternal()")
    class DoFilterInternal {

        @Test
        @DisplayName("adopts the trace id and depth carried by the incoming request's headers for the duration of the chain")
        void adoptsContextFromRequestHeaders() throws ServletException, IOException {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Logged-Trace-Id", "abc123");
            request.addHeader("X-Logged-Depth", "3");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<FlowContext> observedContext = new AtomicReference<>();
            FilterChain chain = (req, res) -> observedContext.set(FlowContextCarrier.capture());

            filter.doFilter(request, response, chain);

            assertThat(observedContext.get()).isEqualTo(new FlowContext("abc123", 3));
        }

        @Test
        @DisplayName("runs the chain directly, adopting nothing, when the request carries no trace id header")
        void runsChainAsIsWhenNoTraceIdHeader() throws ServletException, IOException {
            MockHttpServletRequest request = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<FlowContext> observedContext = new AtomicReference<>(new FlowContext("sentinel", 0));
            FilterChain chain = (req, res) -> observedContext.set(FlowContextCarrier.capture());

            filter.doFilter(request, response, chain);

            assertThat(observedContext.get()).isNull();
        }

        @Test
        @DisplayName("restores this thread's previous context once the chain completes")
        void restoresPreviousContextAfterChain() throws ServletException, IOException {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Logged-Trace-Id", "abc123");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FlowContext outerContext = new FlowContext("outer-trace", 5);
            Runnable restoreOuter = FlowContextCarrier.adopt(outerContext);
            try {
                filter.doFilter(request, response, (req, res) -> { });

                assertThat(FlowContextCarrier.capture()).isEqualTo(outerContext);
            } finally {
                restoreOuter.run();
            }
        }

        @Test
        @DisplayName("propagates a ServletException thrown by the chain, and still restores this thread's previous context")
        void propagatesServletExceptionAndRestoresContext() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Logged-Trace-Id", "abc123");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FlowContext outerContext = new FlowContext("outer-trace", 5);
            Runnable restoreOuter = FlowContextCarrier.adopt(outerContext);
            try {
                FilterChain chain = (req, res) -> {
                    throw new ServletException("boom");
                };

                assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                        .isInstanceOf(ServletException.class)
                        .hasMessage("boom");
                assertThat(FlowContextCarrier.capture()).isEqualTo(outerContext);
            } finally {
                restoreOuter.run();
            }
        }

        @Test
        @DisplayName("propagates an IOException thrown by the chain, and still restores this thread's previous context")
        void propagatesIoExceptionAndRestoresContext() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Logged-Trace-Id", "abc123");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FlowContext outerContext = new FlowContext("outer-trace", 5);
            Runnable restoreOuter = FlowContextCarrier.adopt(outerContext);
            try {
                FilterChain chain = (req, res) -> {
                    throw new IOException("boom");
                };

                assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                        .isInstanceOf(IOException.class)
                        .hasMessage("boom");
                assertThat(FlowContextCarrier.capture()).isEqualTo(outerContext);
            } finally {
                restoreOuter.run();
            }
        }
    }
}
