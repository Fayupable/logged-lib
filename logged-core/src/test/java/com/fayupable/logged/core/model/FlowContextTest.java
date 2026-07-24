package com.fayupable.logged.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlowContextTest {

    @Nested
    @DisplayName("root()")
    class Root {

        @Test
        @DisplayName("starts at depth zero")
        void startsAtDepthZero() {
            FlowContext context = FlowContext.root();

            assertThat(context.depth()).isZero();
        }

        @Test
        @DisplayName("generates a non-null, non-blank trace id")
        void generatesNonBlankTraceId() {
            FlowContext context = FlowContext.root();

            assertThat(context.traceId()).isNotBlank();
        }

        @Test
        @DisplayName("generates different trace ids across separate chains")
        void generatesDifferentTraceIdsAcrossChains() {
            FlowContext first = FlowContext.root();
            FlowContext second = FlowContext.root();

            assertThat(first.traceId()).isNotEqualTo(second.traceId());
        }
    }

    @Nested
    @DisplayName("next()")
    class Next {

        @Test
        @DisplayName("keeps the same trace id as the parent context")
        void keepsSameTraceId() {
            FlowContext parent = FlowContext.root();

            FlowContext child = parent.next();

            assertThat(child.traceId()).isEqualTo(parent.traceId());
        }

        @Test
        @DisplayName("increments depth by one relative to the parent context")
        void incrementsDepth() {
            FlowContext parent = FlowContext.root();

            FlowContext child = parent.next();

            assertThat(child.depth()).isEqualTo(parent.depth() + 1);
        }

        @Test
        @DisplayName("supports multiple levels of nesting")
        void supportsMultipleLevelsOfNesting() {
            FlowContext root = FlowContext.root();

            FlowContext grandchild = root.next().next();

            assertThat(grandchild.depth()).isEqualTo(2);
            assertThat(grandchild.traceId()).isEqualTo(root.traceId());
        }
    }
}