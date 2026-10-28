package com.fayupable.logged.spring.config;

import com.fayupable.logged.core.annotation.CallerMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;

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
    private final Mdc mdc = new Mdc();

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

    public Mdc getMdc() {
        return mdc;
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
         *
         * <p>Deprecated since 1.5.0: behind a proxy that appends to the
         * header the leftmost entry this setting uses can be spoofed. Prefer
         * Spring Boot's {@code server.forward-headers-strategy=native}, which
         * resolves the client address from the right, skipping trusted
         * proxies, and rewrites {@code getRemoteAddr()} for the whole
         * application; this library then picks it up with this setting off.
         * The setting keeps working unchanged.
         */
        private boolean trustForwardedHeaders = false;

        /**
         * How an authenticated caller's name is written into every
         * {@code @Logged} event, unless a method overrides it through
         * {@code @Logged(caller = ...)}.
         *
         * <p>Defaults to {@link CallerMode#NAME}, the historical behavior.
         * Set it to {@code masked} when usernames are e-mail addresses, to
         * keep personal data (GDPR / KVKK) out of logs, or to {@code custom}
         * together with a
         * {@link com.fayupable.logged.spring.security.CallerIdentityResolver}
         * bean to log an internal user id instead. {@code custom} without
         * such a bean fails application startup.
         */
        private CallerMode caller = CallerMode.NAME;

        /**
         * Whether a coarse description of the caller's device, such as
         * {@code "Windows / Chrome 129"}, is recorded for every
         * {@code @Logged} method, not only those annotated with
         * {@code includeDevice = true}.
         *
         * <p>Defaults to {@code false}. IP address plus browser plus version
         * comes close to a device fingerprint, which is personal data under
         * GDPR / KVKK, so the recommended setup keeps this off and enables
         * device info per method only where an audit needs it (login,
         * registration, an OAuth callback).
         */
        private boolean includeDevice = false;

        @Deprecated(since = "1.5.0")
        @DeprecatedConfigurationProperty(
                reason = "The leftmost X-Forwarded-For entry can be spoofed behind a proxy that appends to the "
                        + "header. Let Spring Boot resolve the client address instead and keep this off.",
                replacement = "server.forward-headers-strategy",
                since = "1.5.0")
        public boolean isTrustForwardedHeaders() {
            return trustForwardedHeaders;
        }

        @Deprecated(since = "1.5.0")
        public void setTrustForwardedHeaders(boolean trustForwardedHeaders) {
            this.trustForwardedHeaders = trustForwardedHeaders;
        }

        public CallerMode getCaller() {
            return caller;
        }

        public void setCaller(CallerMode caller) {
            this.caller = caller;
        }

        public boolean isIncludeDevice() {
            return includeDevice;
        }

        public void setIncludeDevice(boolean includeDevice) {
            this.includeDevice = includeDevice;
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

    /**
     * Properties controlling MDC propagation.
     */
    public static class Mdc {

        /**
         * Whether {@link com.fayupable.logged.spring.aspect.LoggedMdcKeys}
         * are written to the current thread's MDC while a {@code @Logged}
         * method runs. Defaults to {@code true}. Disabling this is useful
         * for an application that manages its own MDC entries and wants to
         * avoid any risk of key collision, or that wants to avoid the extra
         * {@code MDC.put}/{@code MDC.remove} calls on a very hot path.
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