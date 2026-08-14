package com.fayupable.logged.test;

import com.fayupable.logged.core.model.MethodInvocationEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

@DisplayName("InMemoryInvocationEventEmitter")
class InMemoryInvocationEventEmitterTest {

    private static MethodInvocationEvent event(String methodName) {
        return new MethodInvocationEvent(
                "SomeClass", methodName, Instant.now(), 1_000_000L, true, null, null, "unknown", null, "trace-1", 0
        );
    }

    @Nested
    @DisplayName("emit")
    class Emit {

        @Test
        @DisplayName("records events in emission order")
        void recordsEventsInOrder() {
            InMemoryInvocationEventEmitter emitter = new InMemoryInvocationEventEmitter();

            emitter.emit(event("first"));
            emitter.emit(event("second"));

            assertThat(emitter.events())
                    .extracting(MethodInvocationEvent::methodName)
                    .containsExactly("first", "second");
        }

        @Test
        @DisplayName("counts recorded events")
        void countsRecordedEvents() {
            InMemoryInvocationEventEmitter emitter = new InMemoryInvocationEventEmitter();

            emitter.emit(event("first"));
            emitter.emit(event("second"));

            assertThat(emitter.count()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("lastEvent")
    class LastEvent {

        @Test
        @DisplayName("returns the most recently emitted event")
        void returnsMostRecentEvent() {
            InMemoryInvocationEventEmitter emitter = new InMemoryInvocationEventEmitter();

            emitter.emit(event("first"));
            emitter.emit(event("second"));

            assertThat(emitter.lastEvent().methodName()).isEqualTo("second");
        }

        @Test
        @DisplayName("throws when nothing has been recorded yet")
        void throwsWhenEmpty() {
            InMemoryInvocationEventEmitter emitter = new InMemoryInvocationEventEmitter();

            assertThatIllegalStateException().isThrownBy(emitter::lastEvent);
        }
    }

    @Nested
    @DisplayName("clear")
    class Clear {

        @Test
        @DisplayName("discards previously recorded events")
        void discardsRecordedEvents() {
            InMemoryInvocationEventEmitter emitter = new InMemoryInvocationEventEmitter();
            emitter.emit(event("first"));

            emitter.clear();

            assertThat(emitter.count()).isZero();
        }
    }
}
