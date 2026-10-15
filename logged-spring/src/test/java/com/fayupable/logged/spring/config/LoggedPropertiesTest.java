package com.fayupable.logged.spring.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoggedProperties")
class LoggedPropertiesTest {

    @Test
    @DisplayName("defaults to enabled")
    void defaultsToEnabled() {
        LoggedProperties properties = new LoggedProperties();

        assertThat(properties.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("setEnabled overrides the default")
    void setEnabledOverridesDefault() {
        LoggedProperties properties = new LoggedProperties();

        properties.setEnabled(false);

        assertThat(properties.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("client info trust-forwarded-headers defaults to false")
    void clientInfoDefaultsToNotTrustingForwardedHeaders() {
        LoggedProperties properties = new LoggedProperties();

        assertThat(properties.getClientInfo().isTrustForwardedHeaders()).isFalse();
    }

    @Test
    @DisplayName("client info trust-forwarded-headers can be overridden")
    void clientInfoTrustForwardedHeadersOverridesDefault() {
        LoggedProperties properties = new LoggedProperties();

        properties.getClientInfo().setTrustForwardedHeaders(true);

        assertThat(properties.getClientInfo().isTrustForwardedHeaders()).isTrue();
    }

    @Test
    @DisplayName("metrics defaults to enabled")
    void metricsDefaultsToEnabled() {
        LoggedProperties properties = new LoggedProperties();

        assertThat(properties.getMetrics().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("metrics enabled can be overridden")
    void metricsEnabledOverridesDefault() {
        LoggedProperties properties = new LoggedProperties();

        properties.getMetrics().setEnabled(false);

        assertThat(properties.getMetrics().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("client info include-device defaults to false and can be enabled")
    void includeDeviceDefaultsToFalse() {
        LoggedProperties properties = new LoggedProperties();
        assertThat(properties.getClientInfo().isIncludeDevice()).isFalse();

        properties.getClientInfo().setIncludeDevice(true);

        assertThat(properties.getClientInfo().isIncludeDevice()).isTrue();
    }
}
