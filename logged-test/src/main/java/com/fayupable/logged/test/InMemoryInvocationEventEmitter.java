package com.fayupable.logged.test;

import com.fayupable.logged.core.model.MethodInvocationEvent;
import com.fayupable.logged.core.port.InvocationEventEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An {@link InvocationEventEmitter} that records every event it receives in
 * memory instead of publishing it anywhere, so a test can assert on what
 * {@code @Logged} actually observed about a call.
 *
 * <p>Registering this as the {@code InvocationEventEmitter} bean (or passing
 * it directly to a framework-free {@code LoggedAspect}) turns the emission
 * side effect into something a test can inspect synchronously, without
 * capturing log output or standing up a real sink.
 *
 * <p>Backed by a {@link CopyOnWriteArrayList} because a {@code @Logged}
 * method under test may itself dispatch work to another thread (for example
 * through {@code FlowContextPropagatingExecutor}); emission from more than
 * one thread must not corrupt the recorded list or lose an event.
 */
public final class InMemoryInvocationEventEmitter implements InvocationEventEmitter {

    private final List<MethodInvocationEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void emit(MethodInvocationEvent event) {
        events.add(event);
    }

    /**
     * Returns every event recorded so far, in emission order.
     *
     * @return an immutable snapshot of the recorded events
     */
    public List<MethodInvocationEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Returns the number of events recorded so far.
     *
     * @return the recorded event count
     */
    public int count() {
        return events.size();
    }

    /**
     * Returns the most recently recorded event.
     *
     * @return the last recorded event
     * @throws IllegalStateException if no event has been recorded yet
     */
    public MethodInvocationEvent lastEvent() {
        if (events.isEmpty()) {
            throw new IllegalStateException("No event has been recorded yet.");
        }
        return events.getLast();
    }

    /**
     * Discards every event recorded so far, so this instance can be reused
     * across test methods without leaking state between them.
     */
    public void clear() {
        events.clear();
    }
}
