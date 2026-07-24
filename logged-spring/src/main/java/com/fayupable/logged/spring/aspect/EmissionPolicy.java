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

        return ThreadLocalRandom.current().nextDouble() < logged.sampleRate();
    }
}