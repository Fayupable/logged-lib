package com.fayupable.logged.core.port;

import com.fayupable.logged.core.model.MethodInvocationEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("No-op port implementations")
class NoOpAdaptersTest {

    @Test
    @DisplayName("NoOpInvocationEventEmitter discards events without throwing")
    void noOpInvocationEventEmitterDoesNotThrow() {
        InvocationEventEmitter emitter = new NoOpInvocationEventEmitter();
        MethodInvocationEvent event = new MethodInvocationEvent(
                "SomeClass", "someMethod", Instant.now(), 1_000_000L, true, null, null, "unknown", "trace-1", 0
        );

        assertThatCode(() -> emitter.emit(event)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NoOpMetricsRecorder discards recordings without throwing")
    void noOpMetricsRecorderDoesNotThrow() {
        MetricsRecorder recorder = new NoOpMetricsRecorder();

        assertThatCode(() -> recorder.record("SomeClass", "someMethod", 1_000_000L, true, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NoOpClientInfoPort reports the caller as unknown")
    void noOpClientInfoPortReportsUnknownCaller() {
        IClientInfoPort clientInfoPort = new NoOpClientInfoPort();

        assertThat(clientInfoPort.resolveCallerIdentity()).isEqualTo("unknown");
    }
}