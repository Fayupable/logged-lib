package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.annotation.Logged;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides whether a single invocation should be emitted as a log event,
 * independently of whether its metrics were recorded.
 *
 * <p>Package-private: this is an internal detail of {@link LoggedAspect},
 * not part of the library's public API.
 */
final class EmissionPolicy {

    private EmissionPolicy() {
    }

    /**
     * Determines whether an invocation should be emitted, following the
     * rule documented on {@link LoggedAspect}: failures and slow calls are
     * always emitted, everything else is subject to sampling.
     *
     * @param logged        the annotation configuring this invocation
     * @param durationNanos the measured duration of the invocation
     * @param success       whether the invocation completed without an
     *                      exception
     * @return {@code true} if the invocation should be emitted as an event
     */
    static boolean shouldEmit(Logged logged, long durationNanos, boolean success) {
        if (!success) {
            return true;
        }

        long durationMs = durationNanos / 1_000_000;
        if (durationMs >= logged.slowThresholdMs()) {
            return true;
        }

        return ThreadLocalRandom.current().nextDouble() < clampSampleRate(logged.sampleRate());
    }

    /**
     * Clamps {@code sampleRate} to the documented {@code [0.0, 1.0]} range
     * of {@link Logged#sampleRate()}.
     *
     * <p>{@code @Logged} annotation attributes cannot enforce a value range
     * at compile time the way a method parameter could with a runtime
     * assertion at the call site, so a consumer can write
     * {@code @Logged(sampleRate = 2.0)} or a negative value without any
     * compiler or startup-time error. Left unclamped, {@link
     * ThreadLocalRandom#nextDouble()}'s {@code [0.0, 1.0)} range makes a
     * value above {@code 1.0} behave as "always sample" and a negative
     * value behave as "never sample" anyway, so this never causes incorrect
     * behavior — but relying on that coincidence would leave the actual
     * intent undefined. Clamping makes the resulting behavior explicit and
     * independent of {@link ThreadLocalRandom}'s specific range.
     *
     * @param sampleRate the raw {@link Logged#sampleRate()} value
     * @return {@code sampleRate} restricted to {@code [0.0, 1.0]}
     */
    private static double clampSampleRate(double sampleRate) {
        return Math.clamp(sampleRate, 0.0, 1.0);
    }
}