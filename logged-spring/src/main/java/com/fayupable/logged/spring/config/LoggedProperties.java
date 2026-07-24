package com.fayupable.logged.spring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for this library, bound under the {@code logged}
 * prefix in an application's own configuration (for example
 * {@code application.yml}).
 *
 * <p>Every property here has a safe default, so an application that
 * declares no {@code logged.*} configuration at all gets the exact same
 * behavior as before this class existed. These properties exist purely as
 * an opt-in escape hatch for the cases documented on each field.
 */
@ConfigurationProperties(prefix = "logged")
public class LoggedProperties {

    /**
     * Global switch for this library's {@code @Logged} interception.
     * Disabling it removes the {@link com.fayupable.logged.spring.aspect.LoggedAspect}
     * bean entirely, so {@code @Logged} methods run with no interception at
     * all, useful for example in test environments.
     */
    private boolean enabled = true;

    private final ClientInfo clientInfo = new ClientInfo();
    private final Metrics metrics = new Metrics();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ClientInfo getClientInfo() {
        return clientInfo;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    /**
     * Properties controlling how the caller's identity is resolved.
     */
    public static class ClientInfo {

        /**
         * Whether {@link com.fayupable.logged.spring.security.SpringSecurityClientInfoAdapter}
         * is allowed to trust the {@code X-Forwarded-For} header when
         * resolving an unauthenticated caller's IP address.
         *
         * <p>Defaults to {@code false}, since this header is controlled by
         * the client and can be forged unless the application sits behind a
         * proxy that strips and re-sets it. Only enable this after
         * confirming that such a proxy is actually in place; see
         * {@link com.fayupable.logged.spring.security.SpringSecurityClientInfoAdapter}
         * for the full explanation.
         */
        private boolean trustForwardedHeaders = false;

        public boolean isTrustForwardedHeaders() {
            return trustForwardedHeaders;
        }

        public void setTrustForwardedHeaders(boolean trustForwardedHeaders) {
            this.trustForwardedHeaders = trustForwardedHeaders;
        }
    }

    /**
     * Properties controlling metrics recording.
     */
    public static class Metrics {

        /**
         * Whether invocation metrics are recorded at all. Defaults to
         * {@code true}. Disabling this falls back to a no-op
         * {@link com.fayupable.logged.core.port.MetricsRecorder} even when a
         * {@code MeterRegistry} bean is present.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}