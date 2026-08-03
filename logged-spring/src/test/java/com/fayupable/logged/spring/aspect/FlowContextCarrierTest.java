package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.model.FlowContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FlowContextCarrier")
class FlowContextCarrierTest {

    @Nested
    @DisplayName("capture()")
    class Capture {

        @Test
        @DisplayName("returns null when no scope is active on this thread")
        void returnsNullWhenNothingActive() {
            assertThat(FlowContextCarrier.capture()).isNull();
        }

        @Test
        @DisplayName("returns the same context FlowContextHolder.snapshot() would")
        void matchesFlowContextHolderSnapshot() {
            FlowContextHolder.FlowScope scope = FlowContextHolder.enter();
            try {
                assertThat(FlowContextCarrier.capture()).isEqualTo(FlowContextHolder.snapshot());
            } finally {
                scope.close();
            }
        }
    }

    @Nested
    @DisplayName("adopt()")
    class Adopt {

        @Test
        @DisplayName("makes the given context active on this thread")
        void makesContextActive() {
            FlowContext context = new FlowContext("trace-xyz", 2);

            Runnable restore = FlowContextCarrier.adopt(context);
            try {
                assertThat(FlowContextCarrier.capture()).isEqualTo(context);
            } finally {
                restore.run();
            }
        }

        @Test
        @DisplayName("restores the previously active context once the returned Runnable is run")
        void restoresPreviousContextOnRun() {
            FlowContextHolder.FlowScope outer = FlowContextHolder.enter();
            try {
                Runnable restore = FlowContextCarrier.adopt(new FlowContext("borrowed-trace", 9));
                restore.run();

                assertThat(FlowContextCarrier.capture()).isEqualTo(outer.context());
            } finally {
                outer.close();
            }
        }

        @Test
        @DisplayName("clears this thread's context when adopting null")
        void clearsContextWhenAdoptingNull() {
            FlowContextHolder.FlowScope outer = FlowContextHolder.enter();
            try {
                Runnable restore = FlowContextCarrier.adopt(null);
                try {
                    assertThat(FlowContextCarrier.capture()).isNull();
                } finally {
                    restore.run();
                }
            } finally {
                outer.close();
            }
        }
    }
}
