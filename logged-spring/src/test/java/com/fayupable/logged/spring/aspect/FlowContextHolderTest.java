package com.fayupable.logged.spring.aspect;

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
}
