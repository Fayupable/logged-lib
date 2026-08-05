package com.fayupable.logged.spring.emitter;

import com.fayupable.logged.core.model.MethodInvocationEvent;
import com.fayupable.logged.core.port.InvocationEventEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link InvocationEventEmitter} that writes each event as a single line of
 * structured JSON through SLF4J, instead of the human-readable line produced
 * by {@link Slf4jInvocationEventEmitter}.
 *
 * <p>This exists for applications that ship their logs to a structured
 * backend (Loki, Elasticsearch, Datadog, and similar) that parses each log
 * line as JSON. Emitting {@link Slf4jInvocationEventEmitter}'s prose line
 * into such a backend either fails to parse or gets indexed as one opaque
 * string field, losing the ability to filter or aggregate on individual
 * fields (for example querying every failed call for a given
 * {@code className}). This emitter writes every {@link MethodInvocationEvent}
 * field as its own JSON property instead, so the backend's own JSON parser
 * indexes them directly.
 *
 * <p>No JSON library is used or required: every field on
 * {@link MethodInvocationEvent} is a primitive, a {@code String}, or an
 * {@link java.time.Instant}, so a small hand-written writer is enough and
 * keeps this class dependency-free, consistent with the rest of this
 * library. String fields are escaped for quotes, backslashes, and control
 * characters before being written, since {@code className}, exception type
 * names, and caller identity ultimately originate from application code or
 * an authenticated principal, not arbitrary user input — but escaping them
 * anyway costs nothing and guarantees the emitted line is always valid JSON.
 *
 * <p>As with {@link Slf4jInvocationEventEmitter}, successful calls are
 * logged at {@code INFO} and failed calls at {@code WARN}, and the
 * exception message is never included, only its simple class name.
 */
public class JsonInvocationEventEmitter implements InvocationEventEmitter {

    private static final Logger log = LoggerFactory.getLogger(JsonInvocationEventEmitter.class);

    @Override
    public void emit(MethodInvocationEvent event) {
        String json = toJson(event);

        if (event.success()) {
            log.info("{}", json);
        } else {
            log.warn("{}", json);
        }
    }

    private static String toJson(MethodInvocationEvent event) {
        StringBuilder json = new StringBuilder(160);
        json.append('{');
        appendString(json, "className", event.className());
        json.append(',');
        appendString(json, "methodName", event.methodName());
        json.append(',');
        appendString(json, "timestamp", event.timestamp().toString());
        json.append(',');
        appendNumber(json, "durationMs", event.durationNanos() / 1_000_000);
        json.append(',');
        appendBoolean(json, "success", event.success());
        json.append(',');
        appendString(json, "exceptionType", event.exceptionType());
        json.append(',');
        appendString(json, "rootCauseType", event.rootCauseType());
        json.append(',');
        appendString(json, "callerIdentity", event.callerIdentity());
        json.append(',');
        appendString(json, "traceId", event.traceId());
        json.append(',');
        appendNumber(json, "depth", event.depth());
        json.append('}');
        return json.toString();
    }

    private static void appendString(StringBuilder json, String name, String value) {
        json.append('"').append(name).append('"').append(':');
        if (value == null) {
            json.append("null");
        } else {
            json.append('"').append(escape(value)).append('"');
        }
    }

    private static void appendNumber(StringBuilder json, String name, long value) {
        json.append('"').append(name).append('"').append(':').append(value);
    }

    private static void appendBoolean(StringBuilder json, String name, boolean value) {
        json.append('"').append(name).append('"').append(':').append(value);
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
