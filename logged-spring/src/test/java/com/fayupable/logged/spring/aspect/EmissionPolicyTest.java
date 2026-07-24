package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.annotation.Logged;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EmissionPolicy")
class EmissionPolicyTest {

    private static Logged loggedWith(long slowThresholdMs, double sampleRate) throws NoSuchMethodException {
        return resolveMethod(slowThresholdMs, sampleRate).getAnnotation(Logged.class);
    }

    private static Method resolveMethod(long slowThresholdMs, double sampleRate) throws NoSuchMethodException {
        if (slowThresholdMs == 100 && sampleRate == 0.0) {
            return AnnotationHolder.class.getMethod("thresholdOneHundredNeverSampled");
        }
        if (slowThresholdMs == 100 && sampleRate == 1.0) {
            return AnnotationHolder.class.getMethod("thresholdOneHundredAlwaysSampled");
        }
        throw new IllegalArgumentException("No fixture method for " + slowThresholdMs + "/" + sampleRate);
    }

    @Nested
    @DisplayName("when the call failed")
    class FailedCall {

        @Test
        @DisplayName("always emits, regardless of duration or sample rate")
        void alwaysEmitsOnFailure() throws NoSuchMethodException {
            Logged logged = loggedWith(100, 0.0);

            assertThat(EmissionPolicy.shouldEmit(logged, 0L, false)).isTrue();
        }
    }

    @Nested
    @DisplayName("slow threshold boundary")
    class SlowThresholdBoundary {

        @Test
        @DisplayName("emits when the duration exactly equals the threshold")
        void emitsWhenDurationEqualsThreshold() throws NoSuchMethodException {
            Logged logged = loggedWith(100, 0.0);
            long exactlyAtThresholdNanos = 100L * 1_000_000L;

            assertThat(EmissionPolicy.shouldEmit(logged, exactlyAtThresholdNanos, true)).isTrue();
        }

        @Test
        @DisplayName("does not emit when the duration is one millisecond below the threshold and sampling is disabled")
        void doesNotEmitJustBelowThreshold() throws NoSuchMethodException {
            Logged logged = loggedWith(100, 0.0);
            long justBelowThresholdNanos = 99L * 1_000_000L;

            assertThat(EmissionPolicy.shouldEmit(logged, justBelowThresholdNanos, true)).isFalse();
        }

        @Test
        @DisplayName("emits when the duration exceeds the threshold")
        void emitsWhenDurationExceedsThreshold() throws NoSuchMethodException {
            Logged logged = loggedWith(100, 0.0);
            long aboveThresholdNanos = 150L * 1_000_000L;

            assertThat(EmissionPolicy.shouldEmit(logged, aboveThresholdNanos, true)).isTrue();
        }
    }

    @Nested
    @DisplayName("sampling")
    class Sampling {

        @Test
        @DisplayName("always emits a fast, successful call when sampleRate is 1.0")
        void alwaysEmitsWhenFullySampled() throws NoSuchMethodException {
            Logged logged = loggedWith(100, 1.0);

            assertThat(EmissionPolicy.shouldEmit(logged, 1L, true)).isTrue();
        }

        @Test
        @DisplayName("never emits a fast, successful call when sampleRate is 0.0")
        void neverEmitsWhenSamplingDisabled() throws NoSuchMethodException {
            Logged logged = loggedWith(100, 0.0);

            assertThat(EmissionPolicy.shouldEmit(logged, 1L, true)).isFalse();
        }
    }

    private interface AnnotationHolder {
        @Logged(slowThresholdMs = 100, sampleRate = 0.0)
        void thresholdOneHundredNeverSampled();

        @Logged(slowThresholdMs = 100, sampleRate = 1.0)
        void thresholdOneHundredAlwaysSampled();
    }
}
