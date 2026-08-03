package com.fayupable.logged.spring.http;

import com.fayupable.logged.core.model.FlowContext;
import com.fayupable.logged.spring.aspect.FlowContextCarrier;
import feign.RequestTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FeignTraceRequestInterceptor")
class FeignTraceRequestInterceptorTest {

    private final FeignTraceRequestInterceptor interceptor = new FeignTraceRequestInterceptor();

    @Nested
    @DisplayName("apply()")
    class Apply {

        @Test
        @DisplayName("writes the active trace id and depth onto the outgoing Feign request template")
        void writesActiveContextOntoTemplate() {
            RequestTemplate template = new RequestTemplate();
            FlowContext active = new FlowContext("outer-trace", 2);
            Runnable restore = FlowContextCarrier.adopt(active);
            try {
                interceptor.apply(template);

                assertThat(template.headers().get("X-Logged-Trace-Id")).containsExactly("outer-trace");
                assertThat(template.headers().get("X-Logged-Depth")).containsExactly("2");
            } finally {
                restore.run();
            }
        }

        @Test
        @DisplayName("writes no trace headers when no call chain is active on this thread")
        void writesNothingWhenNoContextActive() {
            RequestTemplate template = new RequestTemplate();

            interceptor.apply(template);

            assertThat(template.headers()).doesNotContainKey("X-Logged-Trace-Id");
        }
    }
}
