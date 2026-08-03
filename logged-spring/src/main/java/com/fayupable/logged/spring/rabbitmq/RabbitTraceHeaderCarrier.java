package com.fayupable.logged.spring.rabbitmq;

import com.fayupable.logged.core.model.FlowContext;
import com.fayupable.logged.spring.aspect.FlowContextCarrier;
import org.springframework.amqp.core.MessageProperties;

import java.util.regex.Pattern;

/**
 * Carries a {@code @Logged} call chain's {@link FlowContext} across a
 * RabbitMQ message, by writing it into the message's properties when
 * publishing and reading it back when consuming.
 *
 * <p>The Spring AMQP counterpart to
 * {@code com.fayupable.logged.spring.kafka.KafkaTraceHeaderCarrier}: the
 * {@code traceId} and {@code depth} are written as plain entries in
 * {@link MessageProperties#getHeaders()}, never into the message body.
 * RabbitMQ message headers exist specifically for metadata like this: they
 * travel with the message but are entirely separate from whatever
 * serialization format the message body uses, so this never touches or
 * constrains that body's schema.
 *
 * <p>Like every propagation class in this library, nothing here is wired in
 * automatically. A consuming application calls {@link #writeToHeaders} when
 * publishing a message and {@link #readAndAdopt} when consuming one, exactly
 * where it already publishes or consumes RabbitMQ messages.
 */
public final class RabbitTraceHeaderCarrier {

    static final String TRACE_ID_HEADER = "logged-traceId";
    static final String DEPTH_HEADER = "logged-depth";

    /**
     * Matches every {@code traceId} this library can ever itself produce
     * (see {@link com.fayupable.logged.core.model.FlowContext#root()}: up to
     * sixteen hexadecimal characters) and nothing else. A RabbitMQ queue's
     * publishers are typically other services within the same application's
     * own trust boundary, unlike an HTTP endpoint that may be reachable from
     * an untrusted caller, but validating here too costs nothing and keeps
     * this carrier consistent with
     * {@link com.fayupable.logged.spring.http.HttpTraceHeaderCarrier}.
     */
    private static final Pattern VALID_TRACE_ID = Pattern.compile("[0-9a-fA-F]{1,16}");

    private RabbitTraceHeaderCarrier() {
    }

    /**
     * Writes the current thread's {@link FlowContext}, if any is active,
     * into {@code properties} as {@link #TRACE_ID_HEADER}/{@link #DEPTH_HEADER}
     * header entries.
     *
     * <p>Does nothing if no {@code @Logged} call is currently active on this
     * thread: a message published from outside any call chain simply
     * carries no trace headers, exactly as if this method had never been
     * called.
     *
     * @param properties the properties of the message about to be published
     */
    public static void writeToHeaders(MessageProperties properties) {
        FlowContext context = FlowContextCarrier.capture();
        if (context == null) {
            return;
        }
        properties.setHeader(TRACE_ID_HEADER, context.traceId());
        properties.setHeader(DEPTH_HEADER, String.valueOf(context.depth()));
    }

    /**
     * Reads a {@link FlowContext} from {@code properties}, if present,
     * adopts it as the active context on this thread for the duration of
     * {@code work}, and restores this thread's previous context again
     * afterward, regardless of whether {@code work} completes normally or
     * throws.
     *
     * <p>If {@code properties} carries no {@link #TRACE_ID_HEADER} — for
     * example, a message published by a service that does not use this
     * library, or one published from outside any call chain — {@code work}
     * is simply run as-is, with no context adopted. Any {@code @Logged}
     * call made from within it then starts a new chain of its own, exactly
     * as it would without this class involved at all.
     *
     * @param properties the properties of the message being consumed
     * @param work       the message-handling code to run with the message's
     *                   trace context active
     */
    public static void readAndAdopt(MessageProperties properties, Runnable work) {
        FlowContext context = readContext(properties);
        if (context == null) {
            work.run();
            return;
        }

        Runnable restorePreviousContext = FlowContextCarrier.adopt(context);
        try {
            work.run();
        } finally {
            restorePreviousContext.run();
        }
    }

    private static FlowContext readContext(MessageProperties properties) {
        Object traceId = properties.getHeaders().get(TRACE_ID_HEADER);
        if (!(traceId instanceof String traceIdString) || !VALID_TRACE_ID.matcher(traceIdString).matches()) {
            return null;
        }
        return new FlowContext(traceIdString, readDepth(properties));
    }

    private static int readDepth(MessageProperties properties) {
        Object depth = properties.getHeaders().get(DEPTH_HEADER);
        if (!(depth instanceof String depthString)) {
            return 0;
        }
        try {
            return Integer.parseInt(depthString);
        } catch (NumberFormatException malformedDepth) {
            return 0;
        }
    }
}
