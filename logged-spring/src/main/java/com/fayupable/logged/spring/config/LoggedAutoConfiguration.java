package com.fayupable.logged.spring.config;

import com.fayupable.logged.core.annotation.CallerMode;
import com.fayupable.logged.core.port.IClientInfoPort;
import com.fayupable.logged.core.port.InvocationEventEmitter;
import com.fayupable.logged.core.port.MetricsRecorder;
import com.fayupable.logged.core.port.NoOpClientInfoPort;
import com.fayupable.logged.core.port.NoOpMetricsRecorder;
import com.fayupable.logged.spring.aspect.LoggedAspect;
import com.fayupable.logged.spring.emitter.Slf4jInvocationEventEmitter;
import com.fayupable.logged.spring.guard.LoggedTargetGuardBeanPostProcessor;
import com.fayupable.logged.spring.metrics.LazyMetricsRecorder;
import com.fayupable.logged.spring.metrics.MicrometerMetricsRecorder;
import com.fayupable.logged.spring.security.CallerIdentityResolver;
import com.fayupable.logged.spring.security.HttpRequestClientInfoAdapter;
import com.fayupable.logged.spring.security.SpringSecurityClientInfoAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.Authentication;

/**
 * Auto-configuration that wires the default port implementations and
 * {@link LoggedAspect} into a Spring Boot application, so that adding this
 * module as a dependency is enough for {@code @Logged} to work without any
 * further setup.
 *
 * <p>Every bean declared here is guarded by {@link ConditionalOnMissingBean},
 * so an application can override any single collaborator (for example to
 * provide a custom {@link InvocationEventEmitter} that forwards events to a
 * message queue) simply by declaring its own bean of that type; this
 * auto-configuration then backs off for that collaborator only, leaving the
 * others in place.
 *
 * <p>{@link MetricsRecorder} resolves, through {@link LazyMetricsRecorder},
 * to a {@link MicrometerMetricsRecorder} if a {@link MeterRegistry} bean
 * turns out to exist once the application has actually started, and to a
 * no-op recorder otherwise. This choice is deliberately deferred to the
 * first real invocation rather than decided during context refresh: whether
 * a {@code MeterRegistry} bean exists at a given point during
 * auto-configuration processing depends on an ordering between this module
 * and Actuator's own auto-configuration that cannot be reliably pinned
 * across Spring Boot versions, whereas resolving lazily is unaffected by
 * that ordering entirely.
 *
 * <p>{@link IClientInfoPort} resolves in three tiers: {@link SpringSecurityClientInfoAdapter}
 * when Spring Security and Spring Web are both present (authentication-aware,
 * with an IP fallback); {@link HttpRequestClientInfoAdapter} when only
 * Spring Web is present (IP resolution has nothing to do with Spring
 * Security, so a service without it still gets real caller IPs instead of
 * always reporting {@code "unknown"}); and {@link NoOpClientInfoPort} when
 * neither is present.
 */
@AutoConfiguration
@EnableConfigurationProperties(LoggedProperties.class)
public class LoggedAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LoggedAutoConfiguration.class);

    /**
     * Registered as a {@code static} bean method, per Spring's own guidance
     * for {@link org.springframework.beans.factory.config.BeanPostProcessor}
     * beans: this ensures the processor is created early enough to inspect
     * every other bean in this context, without triggering premature
     * instantiation of this configuration class itself.
     *
     * @return the guard that rejects {@code @Logged} on disallowed targets
     */
    @Bean
    public static LoggedTargetGuardBeanPostProcessor loggedTargetGuardBeanPostProcessor() {
        return new LoggedTargetGuardBeanPostProcessor();
    }

    @Bean
    @ConditionalOnMissingBean(InvocationEventEmitter.class)
    public InvocationEventEmitter invocationEventEmitter() {
        return new Slf4jInvocationEventEmitter();
    }

    @Bean
    @ConditionalOnMissingBean(IClientInfoPort.class)
    @ConditionalOnClass({Authentication.class, HttpServletRequest.class})
    public IClientInfoPort springSecurityClientInfoAdapter(LoggedProperties properties,
                                                           ObjectProvider<CallerIdentityResolver> resolverProvider) {
        LoggedProperties.ClientInfo clientInfo = properties.getClientInfo();
        CallerIdentityResolver resolver = resolverProvider.getIfAvailable();
        if (clientInfo.getCaller() == CallerMode.CUSTOM && resolver == null) {
            throw new IllegalStateException("logged.client-info.caller=custom requires a "
                    + CallerIdentityResolver.class.getName() + " bean, but none is defined");
        }
        return new SpringSecurityClientInfoAdapter(trustForwardedHeaders(properties), clientInfo.getCaller(), resolver);
    }

    @Bean
    @ConditionalOnMissingBean(IClientInfoPort.class)
    @ConditionalOnClass(HttpServletRequest.class)
    public IClientInfoPort httpRequestClientInfoAdapter(LoggedProperties properties) {
        return new HttpRequestClientInfoAdapter(trustForwardedHeaders(properties));
    }

    @Bean
    @ConditionalOnMissingBean(IClientInfoPort.class)
    public IClientInfoPort noOpClientInfoPort() {
        return new NoOpClientInfoPort();
    }

    @Bean
    @ConditionalOnMissingBean(MetricsRecorder.class)
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "logged.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MetricsRecorder metricsRecorder(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new LazyMetricsRecorder(meterRegistryProvider);
    }

    @Bean
    @ConditionalOnMissingBean(MetricsRecorder.class)
    public MetricsRecorder noOpMetricsRecorder() {
        return new NoOpMetricsRecorder();
    }

    @Bean
    @ConditionalOnMissingBean(LoggedAspect.class)
    @ConditionalOnProperty(prefix = "logged", name = "enabled", havingValue = "true", matchIfMissing = true)
    public LoggedAspect loggedAspect(InvocationEventEmitter eventEmitter, MetricsRecorder metricsRecorder,
                                      IClientInfoPort clientInfoPort, LoggedProperties properties) {
        return new LoggedAspect(eventEmitter, metricsRecorder, clientInfoPort, properties.getMdc().isEnabled(),
                properties.getClientInfo().isIncludeDevice());
    }

    /**
     * Reads the deprecated {@code logged.client-info.trust-forwarded-headers}
     * setting, which is still honored, and logs a one-time warning when it is
     * enabled. Only one client info adapter bean is ever created, so the
     * warning appears once per application context.
     */
    @SuppressWarnings("deprecation") // deprecated but still honored; this is its single read site
    private static boolean trustForwardedHeaders(LoggedProperties properties) {
        boolean trusted = properties.getClientInfo().isTrustForwardedHeaders();
        if (trusted) {
            log.warn("logged.client-info.trust-forwarded-headers is deprecated: it trusts the leftmost "
                    + "X-Forwarded-For entry, which can be spoofed behind a proxy that appends to the header. "
                    + "Set server.forward-headers-strategy=native instead and turn this setting off.");
        }
        return trusted;
    }
}
