package com.fayupable.logged.spring.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecurityContextTaskDecorator")
class SecurityContextTaskDecoratorTest {

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
        SecurityContextHolder.clearContext();
    }

    private SecurityContext contextAuthenticatedAs(String username) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(username, "password"));
        return context;
    }

    private ThreadPoolTaskExecutor newDecoratedExecutor() {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(1);
        taskExecutor.setMaxPoolSize(1);
        taskExecutor.setTaskDecorator(new SecurityContextTaskDecorator());
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
        @DisplayName("propagates the submitting thread's authenticated user onto the executor thread")
        void propagatesSecurityContext() throws InterruptedException {
            executor = newDecoratedExecutor();
            AtomicReference<String> observedUsername = new AtomicReference<>();

            SecurityContextHolder.setContext(contextAuthenticatedAs("alice"));
            submitAndAwait(executor, () ->
                    observedUsername.set(SecurityContextPropagation.capture().getAuthentication().getName()));

            assertThat(observedUsername.get()).isEqualTo("alice");
        }

        @Test
        @DisplayName("truly restores the executor thread's own security context, not merely masked by the next wrapped task's own adopt()")
        void trulyRestoresSecurityContextOnExecutorThread() throws InterruptedException {
            executor = newDecoratedExecutor();

            SecurityContextHolder.setContext(contextAuthenticatedAs("alice"));
            submitAndAwait(executor, () -> { });
            SecurityContextHolder.clearContext();

            executor.setTaskDecorator(null);
            AtomicReference<Boolean> observedAuthenticatedWithoutDecoration = new AtomicReference<>(true);
            submitAndAwait(executor, () ->
                    observedAuthenticatedWithoutDecoration.set(SecurityContextPropagation.capture().getAuthentication() != null));

            assertThat(observedAuthenticatedWithoutDecoration.get()).isFalse();
        }
    }
}
