package com.fayupable.logged.spring.config;

import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.Level;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import com.fayupable.logged.core.model.MethodInvocationEvent;
import com.fayupable.logged.core.annotation.Logged;
import com.fayupable.logged.core.annotation.CallerMode;
import com.fayupable.logged.core.port.IClientInfoPort;
import com.fayupable.logged.core.port.InvocationEventEmitter;
import com.fayupable.logged.core.port.MetricsRecorder;
import com.fayupable.logged.core.port.NoOpClientInfoPort;
import com.fayupable.logged.core.port.NoOpMetricsRecorder;
import com.fayupable.logged.spring.aspect.LoggedAspect;
import com.fayupable.logged.spring.emitter.Slf4jInvocationEventEmitter;
import com.fayupable.logged.spring.metrics.LazyMetricsRecorder;
import com.fayupable.logged.spring.security.CallerIdentityResolver;
import com.fayupable.logged.spring.security.HttpRequestClientInfoAdapter;
import com.fayupable.logged.spring.security.SpringSecurityClientInfoAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("LoggedAutoConfiguration")
class LoggedAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LoggedAutoConfiguration.class));

    @Nested
    @DisplayName("with no user-defined beans")
    class Defaults {

        @Test
        @DisplayName("wires the default SLF4J event emitter")
        void wiresDefaultEventEmitter() {
            contextRunner.run(context -> {
                assertThat(context).hasSingleBean(InvocationEventEmitter.class);
                assertThat(context.getBean(InvocationEventEmitter.class)).isInstanceOf(Slf4jInvocationEventEmitter.class);
            });
        }

        @Test
        @DisplayName("wires the Spring Security client info adapter when it is available")
        void wiresSpringSecurityClientInfoPort() {
            contextRunner.run(context -> {
                assertThat(context).hasSingleBean(IClientInfoPort.class);
                assertThat(context.getBean(IClientInfoPort.class)).isInstanceOf(SpringSecurityClientInfoAdapter.class);
            });
        }

        @Test
        @DisplayName("wires a lazy metrics recorder that behaves as a no-op when no MeterRegistry is ever present")
        void wiresNoOpMetricsRecorderWithoutMeterRegistry() {
            contextRunner.run(context -> {
                assertThat(context).hasSingleBean(MetricsRecorder.class);
                MetricsRecorder recorder = context.getBean(MetricsRecorder.class);
                assertThat(recorder).isInstanceOf(LazyMetricsRecorder.class);

                assertThatCode(() -> recorder.record("SomeClass", "someMethod", 1_000_000L, true, null))
                        .doesNotThrowAnyException();
            });
        }

        @Test
        @DisplayName("wires a single, non-null LoggedAspect bean")
        void wiresLoggedAspect() {
            contextRunner.run(context -> {
                assertThat(context).hasSingleBean(LoggedAspect.class);
                assertThat(context.getBean(LoggedAspect.class)).isNotNull();
            });
        }
    }

    @Nested
    @DisplayName("with a MeterRegistry present")
    class WithMeterRegistry {

        @Configuration(proxyBeanMethods = false)
        static class MeterRegistryConfiguration {
            @Bean
            SimpleMeterRegistry meterRegistry() {
                return new SimpleMeterRegistry();
            }
        }

        @Test
        @DisplayName("actually records metrics into the registry once invoked")
        void wiresMicrometerMetricsRecorder() {
            contextRunner.withUserConfiguration(MeterRegistryConfiguration.class).run(context -> {
                assertThat(context).hasSingleBean(MetricsRecorder.class);
                MetricsRecorder recorder = context.getBean(MetricsRecorder.class);
                assertThat(recorder).isInstanceOf(LazyMetricsRecorder.class);

                recorder.record("SomeClass", "someMethod", 1_000_000L, true, null);

                SimpleMeterRegistry registry = context.getBean(SimpleMeterRegistry.class);
                assertThat(registry.find("method.invocations").counter()).isNotNull();
            });
        }
    }

    @Nested
    @DisplayName("with a user-defined InvocationEventEmitter")
    class WithUserDefinedEmitter {

        @Configuration(proxyBeanMethods = false)
        static class CustomEmitterConfiguration {
            @Bean
            InvocationEventEmitter customEmitter() {
                return event -> {
                    // custom sink, provided by the consuming application
                };
            }
        }

        @Test
        @DisplayName("backs off and keeps the user's bean instead of the default")
        void backsOffForUserBean() {
            contextRunner.withUserConfiguration(CustomEmitterConfiguration.class).run(context -> {
                assertThat(context).hasSingleBean(InvocationEventEmitter.class);
                assertThat(context.getBean(InvocationEventEmitter.class)).isNotInstanceOf(Slf4jInvocationEventEmitter.class);
            });
        }
    }

    @Nested
    @DisplayName("without Spring Security or Spring Web on the classpath")
    class WithoutSpringSecurityOrWeb {

        @Test
        @DisplayName("falls back to the no-op client info port")
        void fallsBackToNoOpClientInfoPort() {
            contextRunner
                    .withClassLoader(new FilteredClassLoader(Authentication.class, HttpServletRequest.class))
                    .run(context -> {
                        assertThat(context).hasSingleBean(IClientInfoPort.class);
                        assertThat(context.getBean(IClientInfoPort.class)).isInstanceOf(NoOpClientInfoPort.class);
                    });
        }
    }

    @Nested
    @DisplayName("with Spring Web but without Spring Security on the classpath")
    class WithSpringWebButNoSpringSecurity {

        @Test
        @DisplayName("wires the HTTP request client info adapter instead of falling back to no-op")
        void wiresHttpRequestClientInfoAdapter() {
            contextRunner
                    .withClassLoader(new FilteredClassLoader(Authentication.class))
                    .run(context -> {
                        assertThat(context).hasSingleBean(IClientInfoPort.class);
                        assertThat(context.getBean(IClientInfoPort.class)).isInstanceOf(HttpRequestClientInfoAdapter.class);
                    });
        }
    }

    @Nested
    @DisplayName("with logged.enabled=false")
    class WithLoggedDisabled {

        @Test
        @DisplayName("does not wire a LoggedAspect bean")
        void doesNotWireLoggedAspect() {
            contextRunner.withPropertyValues("logged.enabled=false").run(context ->
                    assertThat(context).doesNotHaveBean(LoggedAspect.class));
        }
    }

    @Nested
    @DisplayName("with logged.metrics.enabled=false")
    class WithMetricsDisabled {

        @Configuration(proxyBeanMethods = false)
        static class MeterRegistryConfiguration {
            @Bean
            SimpleMeterRegistry meterRegistry() {
                return new SimpleMeterRegistry();
            }
        }

        @Test
        @DisplayName("falls back to the no-op metrics recorder even when a MeterRegistry is present")
        void fallsBackToNoOpMetricsRecorder() {
            contextRunner
                    .withUserConfiguration(MeterRegistryConfiguration.class)
                    .withPropertyValues("logged.metrics.enabled=false")
                    .run(context -> {
                        assertThat(context).hasSingleBean(MetricsRecorder.class);
                        assertThat(context.getBean(MetricsRecorder.class)).isInstanceOf(NoOpMetricsRecorder.class);
                    });
        }
    }

    @Nested
    @DisplayName("logged.client-info.caller")
    class CallerProperty {

        @Configuration(proxyBeanMethods = false)
        static class ResolverConfiguration {
            @Bean
            CallerIdentityResolver callerIdentityResolver() {
                return authentication -> "42";
            }
        }

        @AfterEach
        void tearDown() {
            SecurityContextHolder.clearContext();
        }

        private String identityFor(IClientInfoPort port, String username) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    username, "password", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            return port.resolveCallerIdentity();
        }

        @Test
        @DisplayName("defaults to NAME, writing the username in full")
        void defaultsToName() {
            contextRunner.run(context -> {
                assertThat(context.getBean(LoggedProperties.class).getClientInfo().getCaller()).isEqualTo(CallerMode.NAME);
                assertThat(identityFor(context.getBean(IClientInfoPort.class), "jane@example.com"))
                        .isEqualTo("user:jane@example.com");
            });
        }

        @Test
        @DisplayName("binds masked and masks e-mail usernames")
        void bindsMasked() {
            contextRunner.withPropertyValues("logged.client-info.caller=masked").run(context ->
                    assertThat(identityFor(context.getBean(IClientInfoPort.class), "jane@example.com"))
                            .isEqualTo("user:j***@example.com"));
        }

        @Test
        @DisplayName("binds custom and uses the CallerIdentityResolver bean")
        void bindsCustomWithResolver() {
            contextRunner
                    .withUserConfiguration(ResolverConfiguration.class)
                    .withPropertyValues("logged.client-info.caller=custom")
                    .run(context -> assertThat(identityFor(context.getBean(IClientInfoPort.class), "jane@example.com"))
                            .isEqualTo("user:42"));
        }

        @Test
        @DisplayName("fails startup for custom without a CallerIdentityResolver bean")
        void failsForCustomWithoutResolver() {
            contextRunner.withPropertyValues("logged.client-info.caller=custom").run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("CallerIdentityResolver");
            });
        }
    }

    @Nested
    @DisplayName("logged.client-info.include-device")
    class IncludeDeviceProperty {

        interface PlainService {
            String call();
        }

        static class PlainServiceImpl implements PlainService {
            @Logged
            @Override
            public String call() {
                return "ok";
            }
        }

        @Configuration(proxyBeanMethods = false)
        static class RecordingConfiguration {
            @Bean
            InMemoryEventSink invocationEventEmitter() {
                return new InMemoryEventSink();
            }

            @Bean
            IClientInfoPort clientInfoPort() {
                return new IClientInfoPort() {
                    @Override
                    public String resolveCallerIdentity() {
                        return "user:42";
                    }

                    @Override
                    public String resolveCallerDevice() {
                        return "Windows / Chrome 129";
                    }
                };
            }
        }

        static class InMemoryEventSink implements InvocationEventEmitter {
            final List<MethodInvocationEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

            @Override
            public void emit(MethodInvocationEvent event) {
                events.add(event);
            }
        }

        private String deviceOfPlainCall(org.springframework.context.ApplicationContext context) {
            AspectJProxyFactory factory = new AspectJProxyFactory(new PlainServiceImpl());
            factory.addAspect(context.getBean(LoggedAspect.class));
            PlainService proxy = factory.getProxy();
            proxy.call();
            return context.getBean(InMemoryEventSink.class).events.get(0).callerDevice();
        }

        @Test
        @DisplayName("is off by default, so a plain @Logged method records no device")
        void offByDefault() {
            contextRunner.withUserConfiguration(RecordingConfiguration.class).run(context -> {
                assertThat(context.getBean(LoggedProperties.class).getClientInfo().isIncludeDevice()).isFalse();
                assertThat(deviceOfPlainCall(context)).isNull();
            });
        }

        @Test
        @DisplayName("records the device for every @Logged method when set to true")
        void recordsDeviceWhenEnabled() {
            contextRunner.withUserConfiguration(RecordingConfiguration.class)
                    .withPropertyValues("logged.client-info.include-device=true")
                    .run(context -> assertThat(deviceOfPlainCall(context)).isEqualTo("Windows / Chrome 129"));
        }
    }

    @Nested
    @DisplayName("deprecated logged.client-info.trust-forwarded-headers")
    class DeprecatedTrustForwardedHeaders {

        private ListAppender<ILoggingEvent> appender;
        private ch.qos.logback.classic.Logger configLogger;

        @BeforeEach
        void attachAppender() {
            configLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LoggedAutoConfiguration.class);
            appender = new ListAppender<>();
            appender.start();
            configLogger.addAppender(appender);
        }

        @AfterEach
        void detachAppender() {
            configLogger.detachAppender(appender);
        }

        private List<ILoggingEvent> deprecationWarnings() {
            return appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .filter(event -> event.getFormattedMessage().contains("trust-forwarded-headers is deprecated"))
                    .toList();
        }

        @Test
        @DisplayName("logs one startup warning when enabled, and still honors the setting")
        @SuppressWarnings("deprecation")
        void warnsOnceWhenEnabled() {
            contextRunner.withPropertyValues("logged.client-info.trust-forwarded-headers=true").run(context -> {
                assertThat(context).hasSingleBean(IClientInfoPort.class);
                assertThat(context.getBean(LoggedProperties.class).getClientInfo().isTrustForwardedHeaders()).isTrue();
                assertThat(deprecationWarnings()).hasSize(1);
                assertThat(deprecationWarnings().get(0).getFormattedMessage())
                        .contains("server.forward-headers-strategy=native");
            });
        }

        @Test
        @DisplayName("also warns once for the HTTP-only adapter")
        void warnsOnceForHttpOnlyAdapter() {
            contextRunner
                    .withClassLoader(new FilteredClassLoader(Authentication.class))
                    .withPropertyValues("logged.client-info.trust-forwarded-headers=true")
                    .run(context -> {
                        assertThat(context.getBean(IClientInfoPort.class)).isInstanceOf(HttpRequestClientInfoAdapter.class);
                        assertThat(deprecationWarnings()).hasSize(1);
                    });
        }

        @Test
        @DisplayName("passes the setting to the adapter: X-Forwarded-For is used only when enabled")
        void passesSettingToAdapter() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("203.0.113.5");
            request.addHeader("X-Forwarded-For", "198.51.100.99");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try {
                contextRunner.withPropertyValues("logged.client-info.trust-forwarded-headers=true").run(context ->
                        assertThat(context.getBean(IClientInfoPort.class).resolveCallerIp()).isEqualTo("198.51.100.99"));
                contextRunner.run(context ->
                        assertThat(context.getBean(IClientInfoPort.class).resolveCallerIp()).isEqualTo("203.0.113.5"));
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        }

        @Test
        @DisplayName("logs nothing when the setting is left at its default")
        void silentByDefault() {
            contextRunner.run(context -> assertThat(deprecationWarnings()).isEmpty());
        }

        @Test
        @DisplayName("logs nothing when the setting is explicitly false")
        void silentWhenFalse() {
            contextRunner.withPropertyValues("logged.client-info.trust-forwarded-headers=false")
                    .run(context -> assertThat(deprecationWarnings()).isEmpty());
        }
    }
}
