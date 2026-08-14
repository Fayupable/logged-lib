package com.fayupable.logged.core.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for automatic observability: invocation logging and metrics
 * collection through an AOP-based interceptor provided by an adapter module
 * (for example logged-spring).
 *
 * <p>This annotation intentionally does not capture method arguments or
 * return values. Only structural information is recorded: which method was
 * called, when, how long it took, whether it succeeded, and who called it.
 * This design avoids leaking sensitive data (passwords, tokens, personal
 * information) into logs by construction, rather than relying on masking
 * rules that could be misconfigured or forgotten.
 *
 * <p>This annotation can only be applied to methods ({@link ElementType#METHOD}).
 * It cannot be applied to fields, constructors, or types, so it can never be
 * placed on an injected dependency (for example a repository field) or on a
 * class declaration itself.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Logged(slowThresholdMs = 300, sampleRate = 0.1)
 * public void updateUserProfile(Long userId) {
 *     // ...
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Logged {

    /**
     * The duration, in milliseconds, above which an invocation is considered
     * slow. Interceptor implementations may use this value to raise the log
     * level (for example from INFO to WARN) for slow calls, so that
     * performance regressions stand out without requiring every call to be
     * inspected manually.
     *
     * @return the slow-call threshold in milliseconds, defaulting to 1000
     */
    long slowThresholdMs() default 1000;

    /**
     * The fraction of invocations that should actually be logged, expressed
     * as a value between 0.0 (never log) and 1.0 (always log).
     *
     * <p>Sampling is evaluated independently for every single call using a
     * random check. It is not a counter, so it does not guarantee that
     * exactly one in every N calls is logged; instead, over a large number
     * of calls the proportion of logged calls converges to this value. This
     * approach requires no shared state, so it remains correct across
     * threads and across multiple application instances.
     *
     * <p>Sampling only reduces log volume. It never affects metrics
     * (invocation counters, duration histograms, error counters), which are
     * always recorded so that dashboards and alerts remain accurate.
     *
     * <p>The default of 1.0 is intended for critical write operations
     * (create, update, delete). Lowering this value is most useful for
     * high-traffic read endpoints where logging every call would create
     * excessive noise.
     *
     * @return the sampling rate, defaulting to 1.0 (log every call)
     */
    double sampleRate() default 1.0;

    /**
     * Whether the caller's IP address should be resolved and recorded
     * alongside whatever else the caller-identity resolver produces,
     * instead of only as a fallback used when no identity is available.
     *
     * <p>By default, an interceptor's caller-identity resolution is an
     * either/or chain: an authenticated principal, if one resolves, takes
     * priority over the caller's IP, which is then discarded. For most
     * {@code @Logged} methods that is the right tradeoff — knowing who
     * called is more useful than knowing where from, and recording both
     * unconditionally would add IP address (personal data under most
     * privacy frameworks) to every log line for no benefit.
     *
     * <p>For a small set of security-sensitive operations — login, password
     * reset, TOTP verification, admin mutations — the IP remains valuable
     * for audit and rate-limiting purposes even when the call also resolves
     * to an authenticated identity, and especially on a failed attempt,
     * where the caller's identity may be unverified or entirely absent.
     * Setting this to {@code true} on exactly those methods records the IP
     * unconditionally, without changing IP resolution for every other
     * {@code @Logged} method in the application.
     *
     * @return whether to record the caller's IP address in addition to
     *         caller identity, defaulting to {@code false}
     */
    boolean includeIp() default false;
}