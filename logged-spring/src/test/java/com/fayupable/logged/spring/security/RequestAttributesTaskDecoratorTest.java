package com.fayupable.logged.spring.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RequestAttributesTaskDecorator")
class RequestAttributesTaskDecoratorTest {

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
        RequestContextHolder.resetRequestAttributes();
    }

    private RequestAttributes attributesFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return new ServletRequestAttributes(request);
    }

    private ThreadPoolTaskExecutor newDecoratedExecutor() {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(1);
        taskExecutor.setMaxPoolSize(1);
        taskExecutor.setTaskDecorator(new RequestAttributesTaskDecorator());
        taskExecutor.initialize();
        return taskExecutor;
    }

    private void submitAndAwait(ThreadPoolTaskExecutor taskExecutor, Runnable command) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        taskExecutor.execute(() -> {
            try {
                command.run();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Nested
    @DisplayName("as a Spring TaskExecutor's TaskDecorator")
    class AsTaskExecutorDecorator {

        @Test
        @DisplayName("propagates the submitting thread's request attributes onto the executor thread")
        void propagatesRequestAttributes() throws InterruptedException {
            executor = newDecoratedExecutor();
            AtomicReference<RequestAttributes> observed = new AtomicReference<>();

            RequestAttributes submitted = attributesFrom("203.0.113.5");
            RequestContextHolder.setRequestAttributes(submitted);
            submitAndAwait(executor, () -> observed.set(RequestAttributesPropagation.capture()));

            assertThat(observed.get()).isSameAs(submitted);
        }

        @Test
        @DisplayName("truly restores the executor thread's own request attributes, not merely masked by the next wrapped task's own adopt()")
        void trulyRestoresRequestAttributesOnExecutorThread() throws InterruptedException {
            executor = newDecoratedExecutor();

            RequestContextHolder.setRequestAttributes(attributesFrom("203.0.113.5"));
            submitAndAwait(executor, () -> { });
            RequestContextHolder.resetRequestAttributes();

            executor.setTaskDecorator(null);
            AtomicReference<RequestAttributes> observedWithoutDecoration = new AtomicReference<>(attributesFrom("0.0.0.0"));
            submitAndAwait(executor, () -> observedWithoutDecoration.set(RequestAttributesPropagation.capture()));

            assertThat(observedWithoutDecoration.get()).isNull();
        }
    }
}
