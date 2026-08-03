package com.fayupable.logged.spring.kafka;

import com.fayupable.logged.core.model.FlowContext;
import com.fayupable.logged.spring.aspect.FlowContextCarrier;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Carries a {@code @Logged} call chain's {@link FlowContext} across a Kafka
 * message, by writing it into the message's headers when producing and
 * reading it back when consuming.
 *
 * <p>The {@code traceId} and {@code depth} are written as plain Kafka
 * headers ({@link Headers}), never into the message's own key or value.
 * Kafka headers exist specifically for metadata like this: they travel with
 * the message but are entirely separate from whatever serialization format
 * (JSON, Avro, protobuf, or anything else) the message's actual payload
 * uses, so this never touches or constrains that payload's schema.
 *
 * <p>This class depends only on {@code org.apache.kafka:kafka-clients}'
 * {@link Headers} interface, not {@code spring-kafka}: every Kafka producer
 * and consumer, whether used directly or through Spring's own
 * {@code KafkaTemplate}/{@code @KafkaListener}, exposes this same interface,
 * so this works regardless of which of those a consuming application uses.
 *
 * <p>Like every propagation class in this library, nothing here is wired in
 * automatically. A consuming application calls {@link #writeToHeaders} when
 * producing a message and {@link #readAndAdopt} when consuming one, exactly
 * where it already produces or consumes Kafka messages.
 */
public final class KafkaTraceHeaderCarrier {

    static final String TRACE_ID_HEADER = "logged-traceId";
    static final String DEPTH_HEADER = "logged-depth";

    /**
     * Matches every {@code traceId} this library can ever itself produce
     * (see {@link com.fayupable.logged.core.model.FlowContext#root()}: up to
     * sixteen hexadecimal characters) and nothing else. A Kafka topic's
     * producers are typically other services within the same application's
     * own trust boundary, unlike an HTTP endpoint that may be reachable from
     * an untrusted caller, but validating here too costs nothing and keeps
     * this carrier consistent with
     * {@link com.fayupable.logged.spring.http.HttpTraceHeaderCarrier}.
     */
    private static final Pattern VALID_TRACE_ID = Pattern.compile("[0-9a-fA-F]{1,16}");

    private KafkaTraceHeaderCarrier() {
    }

    /**
     * Writes the current thread's {@link FlowContext}, if any is active,
     * into {@code headers} as {@link #TRACE_ID_HEADER}/{@link #DEPTH_HEADER}
     * entries.
     *
     * <p>Does nothing if no {@code @Logged} call is currently active on this
     * thread: a message produced from outside any call chain simply carries
     * no trace headers, exactly as if this method had never been called.
     *
     * @param headers the headers of the {@code ProducerRecord} about to be
     *                sent
     */
    public static void writeToHeaders(Headers headers) {
        FlowContext context = FlowContextCarrier.capture();
        if (context == null) {
            return;
        }
        headers.add(TRACE_ID_HEADER, context.traceId().getBytes(StandardCharsets.UTF_8));
        headers.add(DEPTH_HEADER, String.valueOf(context.depth()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads a {@link FlowContext} from {@code headers}, if present, adopts
     * it as the active context on this thread for the duration of
     * {@code work}, and restores this thread's previous context again
     * afterward, regardless of whether {@code work} completes normally or
     * throws.
     *
     * <p>If {@code headers} carries no {@link #TRACE_ID_HEADER} — for
     * example, a message produced by a service that does not use this
     * library, or one produced from outside any call chain — {@code work}
     * is simply run as-is, with no context adopted. Any {@code @Logged}
     * call made from within it then starts a new chain of its own, exactly
     * as it would without this class involved at all.
     *
     * @param headers the headers of the {@code ConsumerRecord} being
     *                processed
     * @param work    the message-handling code to run with the message's
     *                trace context active
     */
    public static void readAndAdopt(Headers headers, Runnable work) {
        FlowContext context = readContext(headers);
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

    private static FlowContext readContext(Headers headers) {
        Header traceIdHeader = headers.lastHeader(TRACE_ID_HEADER);
        if (traceIdHeader == null) {
            return null;
        }
        String traceId = new String(traceIdHeader.value(), StandardCharsets.UTF_8);
        if (!VALID_TRACE_ID.matcher(traceId).matches()) {
            return null;
        }
        int depth = readDepth(headers);
        return new FlowContext(traceId, depth);
    }

    private static int readDepth(Headers headers) {
        Header depthHeader = headers.lastHeader(DEPTH_HEADER);
        if (depthHeader == null) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(depthHeader.value(), StandardCharsets.UTF_8));
        } catch (NumberFormatException malformedDepth) {
            return 0;
        }
    }
}
