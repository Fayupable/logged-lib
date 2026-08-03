package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.model.FlowContext;

/**
 * The sanctioned public entry point for reading and writing this thread's
 * active {@link FlowContext} from outside the {@code aspect} package.
 *
 * <p>{@link FlowContextHolder} deliberately stays package-private: it is an
 * internal detail of {@link LoggedAspect}, not part of this library's public
 * API. But carrying a call chain across a boundary that is not a plain Java
 * thread hand-off — a Kafka or RabbitMQ message, for example — requires code
 * outside this package to read the current {@link FlowContext} on a
 * producing thread and re-establish it on a consuming thread, in modules
 * (this library's own {@code kafka}/{@code rabbitmq} packages today, a
 * consuming application's own carrier tomorrow) that have no reason to see
 * anything else {@link FlowContextHolder} exposes.
 *
 * <p>This class exists to be that one, narrow, intentional door: two
 * methods, mirroring {@link FlowContextHolder#snapshot()} and
 * {@link FlowContextHolder#adopt}, and nothing else. It does not replace
 * {@link FlowContextHolder}; it is a thin, public façade in front of it,
 * kept in the same package specifically so it can call it.
 */
public final class FlowContextCarrier {

    private FlowContextCarrier() {
    }

    /**
     * Returns the {@link FlowContext} currently active on this thread, or
     * {@code null} if no {@code @Logged} call is in progress on this
     * thread.
     *
     * @return the active flow context, or {@code null} if none is active
     */
    public static FlowContext capture() {
        return FlowContextHolder.snapshot();
    }

    /**
     * Makes {@code context} the active {@link FlowContext} on the calling
     * thread and returns a {@link Runnable} that undoes this, restoring
     * whatever context was active before this call.
     *
     * @param context the context to make active, or {@code null} to clear
     *                whatever is currently active
     * @return a {@link Runnable} that restores this thread's previous
     *         context; never {@code null}
     */
    public static Runnable adopt(FlowContext context) {
        return FlowContextHolder.adopt(context);
    }
}
