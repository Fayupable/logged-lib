package com.fayupable.logged.spring.http;

import com.fayupable.logged.core.model.FlowContext;
import com.fayupable.logged.spring.aspect.FlowContextCarrier;
import com.fayupable.logged.spring.support.TraceDepth;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Carries a {@code @Logged} call chain's {@link FlowContext} across a
 * synchronous HTTP call, by writing it into the outgoing request's headers
 * and reading it back from the incoming request's headers.
 *
 * <p>Unlike {@link com.fayupable.logged.spring.kafka.KafkaTraceHeaderCarrier}
 * and {@link com.fayupable.logged.spring.rabbitmq.RabbitTraceHeaderCarrier},
 * which operate on one concrete header type each ({@code Headers},
 * {@code MessageProperties}), no single header type is common to every HTTP
 * client a consuming application might use — {@code RestTemplate}, Feign,
 * {@code WebClient}, and a hand-rolled {@code HttpURLConnection} each expose
 * headers through a different API. This class instead operates on plain
 * {@link BiConsumer}/{@link Function} references, so it can be pointed at
 * whichever client's own header-writing/reading method already exists:
 *
 * <pre>{@code
 * // RestTemplate / a plain HttpHeaders instance
 * HttpTraceHeaderCarrier.writeToHeaders(headers::set);
 *
 * // Feign
 * HttpTraceHeaderCarrier.writeToHeaders(requestTemplate::header);
 *
 * // java.net.HttpURLConnection
 * HttpTraceHeaderCarrier.writeToHeaders(connection::setRequestProperty);
 * }</pre>
 *
 * <p>{@link com.fayupable.logged.spring.http.HttpTraceClientHttpRequestInterceptor},
 * {@link com.fayupable.logged.spring.http.FeignTraceRequestInterceptor},
 * {@link com.fayupable.logged.spring.http.HttpTraceExchangeFilterFunction},
 * and {@link com.fayupable.logged.spring.http.HttpTraceServletFilter} are
 * ready-made integrations built on top of this class for
 * {@code RestTemplate}, Feign, {@code WebClient}, and inbound Servlet
 * requests, respectively. A consuming application using a different HTTP
 * client can still participate in cross-service tracing by calling this
 * class directly, the same way those four classes do internally.
 *
 * <p>Only the {@code traceId} and {@code depth} cross the wire — never any
 * other request data — under the {@value #TRACE_ID_HEADER}/
 * {@value #DEPTH_HEADER} header names.
 *
 * <p>Unlike {@link com.fayupable.logged.spring.kafka.KafkaTraceHeaderCarrier}/
 * {@link com.fayupable.logged.spring.rabbitmq.RabbitTraceHeaderCarrier},
 * whose message headers typically originate from another of a consuming
 * application's own services, an HTTP request's headers can originate from
 * a completely untrusted caller if the endpoint receiving it is reachable
 * from outside the application's own trust boundary. Without validation, an
 * external caller could set {@link #TRACE_ID_HEADER} to an arbitrary string
 * — including one crafted to look like a fabricated log line, if it
 * contains characters like {@code \n} and the receiving side's logging
 * pattern does not escape them — which would then flow, unexamined, into
 * this thread's {@code @Logged} log output and MDC. {@link #readAndAdopt}
 * therefore only accepts a {@link #TRACE_ID_HEADER} value that matches the
 * same shape this library itself always produces (a short hexadecimal
 * string, see {@link com.fayupable.logged.core.model.FlowContext#root()});
 * anything else is treated exactly like a missing header, not merely
 * logged-but-rejected, so no attacker-controlled string ever reaches this
 * thread's context at all.
 */
public final class HttpTraceHeaderCarrier {

    public static final String TRACE_ID_HEADER = "X-Logged-Trace-Id";
    public static final String DEPTH_HEADER = "X-Logged-Depth";

    /**
     * Matches every {@code traceId} this library can ever itself produce
     * (see {@link com.fayupable.logged.core.model.FlowContext#root()}: up to
     * sixteen hexadecimal characters, the string form of a 64-bit value) and
     * nothing else, so a value crafted to inject control characters (for
     * example {@code \n}) into log output can never pass this check.
     */
    private static final Pattern VALID_TRACE_ID = Pattern.compile("[0-9a-fA-F]{1,16}");

    private HttpTraceHeaderCarrier() {
    }

    /**
     * Writes the current thread's {@link FlowContext}, if any is active,
     * through {@code headerWriter} under {@link #TRACE_ID_HEADER}/
     * {@link #DEPTH_HEADER}.
     *
     * <p>Does nothing if no {@code @Logged} call is currently active on this
     * thread: an outgoing request made from outside any call chain simply
     * carries no trace headers, exactly as if this method had never been
     * called.
     *
     * @param headerWriter a reference to the target's own
     *                      {@code (name, value) -> void} header-writing
     *                      method
     */
    public static void writeToHeaders(BiConsumer<String, String> headerWriter) {
        FlowContext context = FlowContextCarrier.capture();
        if (context == null) {
            return;
        }
        headerWriter.accept(TRACE_ID_HEADER, context.traceId());
        headerWriter.accept(DEPTH_HEADER, String.valueOf(context.depth()));
    }

    /**
     * Reads a {@link FlowContext} through {@code headerReader}, if present,
     * adopts it as the active context on this thread for the duration of
     * {@code work}, and restores this thread's previous context again
     * afterward, regardless of whether {@code work} completes normally or
     * throws.
     *
     * <p>If no {@link #TRACE_ID_HEADER} is found, or its value does not
     * match {@link #VALID_TRACE_ID} — for example, a request from a caller
     * that does not use this library, or one crafted by an untrusted caller
     * to inject something other than a real trace id — {@code work} is
     * simply run as-is, with no context adopted. Any {@code @Logged} call
     * made from within it then starts a new chain of its own, exactly as it
     * would without this class involved at all.
     *
     * @param headerReader a reference to the source's own
     *                      {@code (name) -> String} header-reading method;
     *                      may return {@code null} for a missing header
     * @param work          the request-handling code to run with the
     *                      incoming request's trace context active
     */
    public static void readAndAdopt(Function<String, String> headerReader, Runnable work) {
        String traceId = headerReader.apply(TRACE_ID_HEADER);
        if (traceId == null || !VALID_TRACE_ID.matcher(traceId).matches()) {
            work.run();
            return;
        }

        FlowContext context = new FlowContext(traceId, readDepth(headerReader));
        Runnable restorePreviousContext = FlowContextCarrier.adopt(context);
        try {
            work.run();
        } finally {
            restorePreviousContext.run();
        }
    }

    private static int readDepth(Function<String, String> headerReader) {
        return TraceDepth.parse(headerReader.apply(DEPTH_HEADER));
    }
}
