package com.fayupable.logged.spring.security;

import com.fayupable.logged.core.annotation.Logged;
import com.fayupable.logged.core.model.MethodInvocationEvent;
import com.fayupable.logged.core.port.InvocationEventEmitter;
import com.fayupable.logged.core.port.MetricsRecorder;
import com.fayupable.logged.spring.aspect.LoggedAspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates, with real emitted events printed to the console, the exact
 * gap {@link SecurityContextPropagatingExecutor} closes: a nested
 * {@code @Logged} call dispatched through a plain, unwrapped {@link Executor}
 * loses the authenticated caller entirely on the executor thread, even
 * though nothing else about the call is wrong.
 */
@DisplayName("Nested async caller identity propagation (demo)")
class NestedAsyncCallerIdentityDemoTest {

    private final List<MethodInvocationEvent> events = new ArrayList<>();
    private ExecutorService rawExecutor;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (rawExecutor != null) {
            rawExecutor.shutdownNow();
        }
    }

    private LoggedAspect newAspect() {
        InvocationEventEmitter emitter = event -> {
            events.add(event);
            System.out.println("EMITTED -> " + event);
        };
        MetricsRecorder metricsRecorder = (className, methodName, durationNanos, success, exceptionType) -> { };
        return new LoggedAspect(emitter, metricsRecorder, new SpringSecurityClientInfoAdapter(false));
    }

    private <T> T proxy(T target, LoggedAspect aspect) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return factory.getProxy();
    }

    private void authenticateAs(String username) {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "password", authorities));
    }

    interface InnerService {
        void doWork();
    }

    static class InnerServiceImpl implements InnerService {
        @Logged(sampleRate = 1.0)
        @Override
        public void doWork() {
            // no-op: only the call itself matters for this demo
        }
    }

    interface OuterService {
        void process(Executor executor) throws InterruptedException;
    }

    static class OuterServiceImpl implements OuterService {
        private final InnerService inner;
        private final CountDownLatch innerCallFinished;

        OuterServiceImpl(InnerService inner, CountDownLatch innerCallFinished) {
            this.inner = inner;
            this.innerCallFinished = innerCallFinished;
        }

        @Logged(sampleRate = 1.0)
        @Override
        public void process(Executor executor) throws InterruptedException {
            executor.execute(() -> {
                inner.doWork();
                innerCallFinished.countDown();
            });
            innerCallFinished.await(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("BEFORE: a plain, unwrapped executor loses the authenticated user for the nested call")
    void withoutPropagation_innerCallLosesCallerIdentity() throws InterruptedException {
        LoggedAspect aspect = newAspect();
        rawExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch latch = new CountDownLatch(1);
        InnerService inner = proxy(new InnerServiceImpl(), aspect);
        OuterService outer = proxy(new OuterServiceImpl(inner, latch), aspect);

        authenticateAs("alice");
        System.out.println("--- WITHOUT SecurityContextPropagatingExecutor ---");
        outer.process(rawExecutor);

        MethodInvocationEvent innerEvent = events.stream()
                .filter(e -> e.methodName().equals("doWork"))
                .findFirst().orElseThrow();

        System.out.println("Inner call caller identity = " + innerEvent.callerIdentity());
        assertThat(innerEvent.callerIdentity()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("AFTER: SecurityContextPropagatingExecutor keeps the authenticated user for the nested call")
    void withPropagation_innerCallKeepsCallerIdentity() throws InterruptedException {
        LoggedAspect aspect = newAspect();
        rawExecutor = Executors.newSingleThreadExecutor();
        Executor propagatingExecutor = new SecurityContextPropagatingExecutor(rawExecutor);
        CountDownLatch latch = new CountDownLatch(1);
        InnerService inner = proxy(new InnerServiceImpl(), aspect);
        OuterService outer = proxy(new OuterServiceImpl(inner, latch), aspect);

        authenticateAs("alice");
        System.out.println("--- WITH SecurityContextPropagatingExecutor ---");
        outer.process(propagatingExecutor);

        MethodInvocationEvent innerEvent = events.stream()
                .filter(e -> e.methodName().equals("doWork"))
                .findFirst().orElseThrow();

        System.out.println("Inner call caller identity = " + innerEvent.callerIdentity());
        assertThat(innerEvent.callerIdentity()).isEqualTo("user:alice");
    }
}
