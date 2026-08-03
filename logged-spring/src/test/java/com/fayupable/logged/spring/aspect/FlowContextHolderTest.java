package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.model.FlowContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FlowContextHolder")
class FlowContextHolderTest {

    @Nested
    @DisplayName("enter()")
    class Enter {

        @Test
        @DisplayName("starts a root context at depth zero when nothing is active")
        void startsRootContext() {
            FlowContextHolder.FlowScope scope = FlowContextHolder.enter();
            try {
                assertThat(scope.context().depth()).isZero();
            } finally {
                scope.close();
            }
        }

        @Test
        @DisplayName("nests one level deeper, sharing the trace id, when a scope is already open")
        void nestsOneLevelDeeper() {
            FlowContextHolder.FlowScope outer = FlowContextHolder.enter();
            try {
                FlowContextHolder.FlowScope inner = FlowContextHolder.enter();
                try {
                    assertThat(inner.context().depth()).isEqualTo(1);
                    assertThat(inner.context().traceId()).isEqualTo(outer.context().traceId());
                } finally {
                    inner.close();
                }
            } finally {
                outer.close();
            }
        }
    }

    @Nested
    @DisplayName("close()")
    class Close {

        @Test
        @DisplayName("clears the thread's context entirely once the root scope is closed")
        void clearsContextAfterRootScopeCloses() {
            FlowContextHolder.FlowScope root = FlowContextHolder.enter();
            String firstTraceId = root.context().traceId();
            root.close();

            FlowContextHolder.FlowScope next = FlowContextHolder.enter();
            try {
                assertThat(next.context().depth()).isZero();
                assertThat(next.context().traceId()).isNotEqualTo(firstTraceId);
            } finally {
                next.close();
            }
        }

        @Test
        @DisplayName("restores the outer scope's context once an inner scope closes")
        void restoresOuterContextAfterInnerScopeCloses() {
            FlowContextHolder.FlowScope outer = FlowContextHolder.enter();
            try {
                FlowContextHolder.FlowScope inner = FlowContextHolder.enter();
                inner.close();

                FlowContextHolder.FlowScope afterInnerClosed = FlowContextHolder.enter();
                try {
                    assertThat(afterInnerClosed.context().depth()).isEqualTo(1);
                    assertThat(afterInnerClosed.context().traceId()).isEqualTo(outer.context().traceId());
                } finally {
                    afterInnerClosed.close();
                }
            } finally {
                outer.close();
            }
        }
    }

    @Nested
    @DisplayName("snapshot()")
    class Snapshot {

        @Test
        @DisplayName("returns null when no scope is active on this thread")
        void returnsNullWhenNothingActive() {
            assertThat(FlowContextHolder.snapshot()).isNull();
        }

        @Test
        @DisplayName("returns the context of the innermost open scope")
        void returnsInnermostContext() {
            FlowContextHolder.FlowScope outer = FlowContextHolder.enter();
            try {
                FlowContextHolder.FlowScope inner = FlowContextHolder.enter();
                try {
                    assertThat(FlowContextHolder.snapshot()).isEqualTo(inner.context());
                } finally {
                    inner.close();
                }
            } finally {
                outer.close();
            }
        }
    }

    @Nested
    @DisplayName("adopt()")
    class Adopt {

        @Test
        @DisplayName("makes the given context active on this thread")
        void makesContextActive() {
            FlowContext context = new FlowContext("trace-xyz", 3);

            Runnable restore = FlowContextHolder.adopt(context);
            try {
                assertThat(FlowContextHolder.snapshot()).isEqualTo(context);
            } finally {
                restore.run();
            }
        }

        @Test
        @DisplayName("does not derive a deeper context, unlike enter()")
        void doesNotIncrementDepth() {
            FlowContext context = new FlowContext("trace-xyz", 3);

            Runnable restore = FlowContextHolder.adopt(context);
            try {
                assertThat(FlowContextHolder.snapshot().depth()).isEqualTo(3);
            } finally {
                restore.run();
            }
        }

        @Test
        @DisplayName("restores the previously active context once the returned Runnable is run")
        void restoresPreviousContextOnRun() {
            FlowContextHolder.FlowScope outer = FlowContextHolder.enter();
            try {
                FlowContext adoptedContext = new FlowContext("borrowed-trace", 9);
                Runnable restore = FlowContextHolder.adopt(adoptedContext);
                restore.run();

                assertThat(FlowContextHolder.snapshot()).isEqualTo(outer.context());
            } finally {
                outer.close();
            }
        }

        @Test
        @DisplayName("clears the thread's context once the returned Runnable is run, when nothing was active before")
        void clearsContextOnRunWhenNothingWasActiveBefore() {
            FlowContext adoptedContext = new FlowContext("borrowed-trace", 9);
            Runnable restore = FlowContextHolder.adopt(adoptedContext);
            restore.run();

            assertThat(FlowContextHolder.snapshot()).isNull();
        }

        @Test
        @DisplayName("removes any active context when adopting null")
        void removesContextWhenAdoptingNull() {
            FlowContextHolder.FlowScope outer = FlowContextHolder.enter();
            try {
                Runnable restore = FlowContextHolder.adopt(null);
                try {
                    assertThat(FlowContextHolder.snapshot()).isNull();
                } finally {
                    restore.run();
                }
            } finally {
                outer.close();
            }
        }
    }
}
