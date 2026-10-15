package com.fayupable.logged.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MethodInvocationEvent")
class MethodInvocationEventTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-10-15T10:00:00Z");

    @Test
    @DisplayName("the canonical constructor keeps every field, including callerDevice")
    void canonicalConstructorKeepsCallerDevice() {
        MethodInvocationEvent event = new MethodInvocationEvent("AuthService", "login", TIMESTAMP, 42L,
                false, "IllegalStateException", "SQLException", "user:42", "203.0.113.5",
                "Windows / Chrome 129", "abc123", 1);

        assertThat(event.className()).isEqualTo("AuthService");
        assertThat(event.methodName()).isEqualTo("login");
        assertThat(event.timestamp()).isEqualTo(TIMESTAMP);
        assertThat(event.durationNanos()).isEqualTo(42L);
        assertThat(event.success()).isFalse();
        assertThat(event.exceptionType()).isEqualTo("IllegalStateException");
        assertThat(event.rootCauseType()).isEqualTo("SQLException");
        assertThat(event.callerIdentity()).isEqualTo("user:42");
        assertThat(event.callerIp()).isEqualTo("203.0.113.5");
        assertThat(event.callerDevice()).isEqualTo("Windows / Chrome 129");
        assertThat(event.traceId()).isEqualTo("abc123");
        assertThat(event.depth()).isEqualTo(1);
    }

    @Test
    @DisplayName("the pre-1.4.0 constructor still compiles and leaves callerDevice null")
    void legacyConstructorLeavesCallerDeviceNull() {
        MethodInvocationEvent legacy = new MethodInvocationEvent("AuthService", "login", TIMESTAMP, 42L,
                false, "IllegalStateException", "SQLException", "user:42", "203.0.113.5", "abc123", 1);

        MethodInvocationEvent canonical = new MethodInvocationEvent("AuthService", "login", TIMESTAMP, 42L,
                false, "IllegalStateException", "SQLException", "user:42", "203.0.113.5", null, "abc123", 1);

        assertThat(legacy.callerDevice()).isNull();
        assertThat(legacy).isEqualTo(canonical);
        assertThat(legacy).hasSameHashCodeAs(canonical);
    }
}
