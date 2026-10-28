package com.fayupable.logged.spring.support;

/**
 * Parses the call-chain depth received from another service (an HTTP header,
 * a Kafka or RabbitMQ message header), shared by every trace carrier so they
 * apply exactly the same rule.
 *
 * <p>The value comes from outside this process and is only trusted as far as
 * keeping it in a sensible range: anything missing, malformed or negative
 * starts a new chain at depth 0, and anything above {@value #MAX_DEPTH} is
 * capped there, so a forged header can neither produce a negative depth nor
 * overflow when a nested call increments it.
 *
 * <p>This class is used internally by this library's trace carriers. It is
 * public only because they live in different packages; it is not part of the
 * supported API and may change without notice.
 */
public final class TraceDepth {

    /** Highest depth accepted from another service; no real call chain gets close. */
    public static final int MAX_DEPTH = 1000;

    private TraceDepth() {
    }

    /**
     * @param raw the depth as received, may be {@code null}
     * @return the depth clamped to {@code 0..}{@value #MAX_DEPTH}, or 0 when
     *         {@code raw} is missing or not a whole number
     */
    public static int parse(String raw) {
        if (raw == null) {
            return 0;
        }
        try {
            return Math.min(Math.max(Integer.parseInt(raw.trim()), 0), MAX_DEPTH);
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }
}
