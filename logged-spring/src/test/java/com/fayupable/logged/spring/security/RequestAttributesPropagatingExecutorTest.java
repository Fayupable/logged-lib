package com.fayupable.logged.spring.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RequestAttributesPropagatingExecutor")
class RequestAttributesPropagatingExecutorTest {

    private ExecutorService delegate;

    @AfterEach
    void tearDown() {
        if (delegate != null) {
            delegate.shutdownNow();
        }
        RequestContextHolder.resetRequestAttributes();
    }

    private RequestAttributes attributesFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return new ServletRequestAttributes(request);
    }

    private void executeAndAwait(RequestAttributesPropagatingExecutor executor, Runnable command) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                command.run();
            } finally {
                latch.countDown();
            }
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Nested
    @DisplayName("on a platform thread pool")
    class OnPlatformThreadPool {

        @Test
        @DisplayName("propagates the submitting thread's request attributes onto the pool thread")
        void propagatesRequestAttributes() throws InterruptedException {
            delegate = Executors.newFixedThreadPool(1);
            RequestAttributesPropagatingExecutor executor = new RequestAttributesPropagatingExecutor(delegate);
            AtomicReference<RequestAttributes> observed = new AtomicReference<>();

            RequestAttributes submitted = attributesFrom("203.0.113.5");
            RequestContextHolder.setRequestAttributes(submitted);
            executeAndAwait(executor, () -> observed.set(RequestAttributesPropagation.capture()));

            assertThat(observed.get()).isSameAs(submitted);
        }

        @Test
        @DisplayName("propagates null when there is no active request on the submitting thread")
        void propagatesNullWhenNothingActive() throws InterruptedException {
            delegate = Executors.newFixedThreadPool(1);
            RequestAttributesPropagatingExecutor executor = new RequestAttributesPropagatingExecutor(delegate);
            AtomicReference<RequestAttributes> observed = new AtomicReference<>(attributesFrom("0.0.0.0"));

            executeAndAwait(executor, () -> observed.set(RequestAttributesPropagation.capture()));

            assertThat(observed.get()).isNull();
        }

        @Test
        @DisplayName("truly restores the executor thread's own request attributes, not merely masked by the next wrapped task's own adopt()")
        void trulyRestoresRequestAttributesOnExecutorThread() throws InterruptedException {
            delegate = Executors.newFixedThreadPool(1);
            RequestAttributesPropagatingExecutor executor = new RequestAttributesPropagatingExecutor(delegate);

            RequestContextHolder.setRequestAttributes(attributesFrom("203.0.113.5"));
            executeAndAwait(executor, () -> { });
            RequestContextHolder.resetRequestAttributes();

            AtomicReference<RequestAttributes> observedWithoutWrapper = new AtomicReference<>(attributesFrom("0.0.0.0"));
            CountDownLatch latch = new CountDownLatch(1);
            delegate.execute(() -> {
                observedWithoutWrapper.set(RequestAttributesPropagation.capture());
                latch.countDown();
            });
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(observedWithoutWrapper.get()).isNull();
        }

        @Test
        @DisplayName("still restores the executor thread's request attributes when the submitted task throws")
        void restoresAttributesEvenWhenTaskThrows() throws InterruptedException {
            delegate = Executors.newFixedThreadPool(1);
            RequestAttributesPropagatingExecutor executor = new RequestAttributesPropagatingExecutor(delegate);

            RequestContextHolder.setRequestAttributes(attributesFrom("203.0.113.5"));
            CountDownLatch latch = new CountDownLatch(1);
            executor.execute(() -> {
                try {
                    throw new IllegalStateException("boom");
                } finally {
                    latch.countDown();
                }
            });
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            RequestContextHolder.resetRequestAttributes();

            AtomicReference<RequestAttributes> observedAfterFailure = new AtomicReference<>(attributesFrom("0.0.0.0"));
            executeAndAwait(executor, () -> observedAfterFailure.set(RequestAttributesPropagation.capture()));

            assertThat(observedAfterFailure.get()).isNull();
        }
    }

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("rejects a null delegate")
        void rejectsNullDelegate() {
            assertThatThrownBy(() -> new RequestAttributesPropagatingExecutor(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
