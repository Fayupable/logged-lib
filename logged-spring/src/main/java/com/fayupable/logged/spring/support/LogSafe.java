package com.fayupable.logged.spring.support;

/**
 * Keeps a value that may come from outside the application (a username, an
 * {@code X-Forwarded-For} entry, a custom resolver's result) on a single line
 * when it is written into plain-text log output or the MDC.
 *
 * <p>Without this, a value containing a line break could forge an extra,
 * genuine-looking log line (log injection). Control characters are replaced
 * by visible escapes (a backslash followed by {@code n}, {@code r}, {@code t},
 * or {@code u} and four hex digits), in the same style
 * {@link com.fayupable.logged.spring.emitter.JsonInvocationEventEmitter}
 * uses, rather than removed, so an injection attempt stays visible in the
 * log. Escaped are C0 controls and DEL, C1 controls (including NEL,
 * {@code U+0085}) and the Unicode line and paragraph separators
 * ({@code U+2028}, {@code U+2029}), which some log viewers also treat as
 * line breaks. Everything else, including non-ASCII letters, is kept as is.
 *
 * <p>The common case costs a single scan: a value with nothing to escape is
 * returned as the same instance, without copying.
 *
 * <p>This class is used internally by this library's emitters and MDC
 * propagation. It is public only because those live in different packages;
 * it is not part of the supported API and may change without notice.
 */
public final class LogSafe {

    private LogSafe() {
    }

    /**
     * Returns {@code value} with every line-breaking or invisible control
     * character replaced by a visible escape.
     *
     * @param value the value to make safe, may be {@code null}
     * @return {@code value} itself when nothing needs escaping, an escaped
     *         copy otherwise, or {@code null} for {@code null}
     */
    public static String singleLine(String value) {
        if (value == null || !containsUnsafe(value)) {
            return value;
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            appendEscaped(escaped, value.charAt(i));
        }
        return escaped.toString();
    }

    private static boolean containsUnsafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (isUnsafe(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnsafe(char c) {
        return c < 0x20 || (c >= 0x7F && c <= 0x9F) || c == '\u2028' || c == '\u2029';
    }

    private static void appendEscaped(StringBuilder out, char c) {
        switch (c) {
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            default -> {
                if (isUnsafe(c)) {
                    out.append(String.format("\\u%04x", (int) c));
                } else {
                    out.append(c);
                }
            }
        }
    }
}
