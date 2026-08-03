package com.fayupable.logged.spring.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SecurityContextPropagatingExecutor")
class SecurityContextPropagatingExecutorTest {

    private ExecutorService delegate;

    @AfterEach
    void tearDown() {
        if (delegate != null) {
            delegate.shutdownNow();
        }
        SecurityContextHolder.clearContext();
    }

    private SecurityContext contextAuthenticatedAs(String username) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(username, "password"));
        return context;
    }

    private void executeAndAwait(SecurityContextPropagatingExecutor executor, Runnable command) throws InterruptedException {
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
        @DisplayName("propagates the submitting thread's authenticated user onto the pool thread")
        void propagatesSecurityContext() throws InterruptedException {
            delegate = Executors.newFixedThreadPool(1);
            SecurityContextPropagatingExecutor executor = new SecurityContextPropagatingExecutor(delegate);
            AtomicReference<String> observedUsername = new AtomicReference<>();

            SecurityContextHolder.setContext(contextAuthenticatedAs("alice"));
            executeAndAwait(executor, () ->
                    observedUsername.set(SecurityContextPropagation.capture().getAuthentication().getName()));

            assertThat(observedUsername.get()).isEqualTo("alice");
        }

        @Test
        @DisplayName("truly restores the executor thread's own security context, not merely masked by the next wrapped task's own adopt()")
        void trulyRestoresSecurityContextOnExecutorThread() throws InterruptedException {
            delegate = Executors.newFixedThreadPool(1);
            SecurityContextPropagatingExecutor executor = new SecurityContextPropagatingExecutor(delegate);

            SecurityContextHolder.setContext(contextAuthenticatedAs("alice"));
            executeAndAwait(executor, () -> { });
            SecurityContextHolder.clearContext();

            AtomicReference<Boolean> observedAuthenticatedWithoutWrapper = new AtomicReference<>(true);
            CountDownLatch latch = new CountDownLatch(1);
            delegate.execute(() -> {
                observedAuthenticatedWithoutWrapper.set(SecurityContextPropagation.capture().getAuthentication() != null);
                latch.countDown();
            });
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(observedAuthenticatedWithoutWrapper.get()).isFalse();
        }

        @Test
        @DisplayName("still restores the executor thread's security context when the submitted task throws")
        void restoresContextEvenWhenTaskThrows() throws InterruptedException {
            delegate = Executors.newFixedThreadPool(1);
            SecurityContextPropagatingExecutor executor = new SecurityContextPropagatingExecutor(delegate);

            SecurityContextHolder.setContext(contextAuthenticatedAs("alice"));
            CountDownLatch latch = new CountDownLatch(1);
            executor.execute(() -> {
                try {
                    throw new IllegalStateException("boom");
                } finally {
                    latch.countDown();
                }
            });
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            SecurityContextHolder.clearContext();

            AtomicReference<Boolean> observedAuthenticatedAfterFailure = new AtomicReference<>(true);
            executeAndAwait(executor, () ->
                    observedAuthenticatedAfterFailure.set(SecurityContextPropagation.capture().getAuthentication() != null));

            assertThat(observedAuthenticatedAfterFailure.get()).isFalse();
        }
    }

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("rejects a null delegate")
        void rejectsNullDelegate() {
            assertThatThrownBy(() -> new SecurityContextPropagatingExecutor(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
