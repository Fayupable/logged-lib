package com.fayupable.logged.spring.aspect;

import com.fayupable.logged.core.model.FlowContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MdcPropagation")
class MdcPropagationTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Nested
    @DisplayName("push()")
    class Push {

        @Test
        @DisplayName("writes trace id, depth, class name, and method name under LoggedMdcKeys")
        void writesAllKeys() {
            FlowContext context = new FlowContext("trace-abc", 2);

            MdcPropagation.push(context, "OrderService", "placeOrder", null);

            assertThat(MDC.get(LoggedMdcKeys.TRACE_ID)).isEqualTo("trace-abc");
            assertThat(MDC.get(LoggedMdcKeys.DEPTH)).isEqualTo("2");
            assertThat(MDC.get(LoggedMdcKeys.CLASS_NAME)).isEqualTo("OrderService");
            assertThat(MDC.get(LoggedMdcKeys.METHOD_NAME)).isEqualTo("placeOrder");
        }
    }

    @Nested
    @DisplayName("the Runnable returned by push()")
    class Restore {

        @Test
        @DisplayName("removes all keys when nothing was present before push()")
        void removesKeysWhenNothingWasActiveBefore() {
            FlowContext context = new FlowContext("trace-abc", 0);

            Runnable restore = MdcPropagation.push(context, "OrderService", "placeOrder", null);
            restore.run();

            assertThat(MDC.get(LoggedMdcKeys.TRACE_ID)).isNull();
            assertThat(MDC.get(LoggedMdcKeys.DEPTH)).isNull();
            assertThat(MDC.get(LoggedMdcKeys.CLASS_NAME)).isNull();
            assertThat(MDC.get(LoggedMdcKeys.METHOD_NAME)).isNull();
        }

        @Test
        @DisplayName("restores the outer call's values once a nested call's push() is undone")
        void restoresOuterValuesAfterNestedCall() {
            FlowContext outerContext = new FlowContext("outer-trace", 0);
            Runnable restoreOuter = MdcPropagation.push(outerContext, "ServiceA", "process", null);

            FlowContext innerContext = new FlowContext("outer-trace", 1);
            Runnable restoreInner = MdcPropagation.push(innerContext, "ServiceB", "doWork", null);
            restoreInner.run();

            assertThat(MDC.get(LoggedMdcKeys.DEPTH)).isEqualTo("0");
            assertThat(MDC.get(LoggedMdcKeys.CLASS_NAME)).isEqualTo("ServiceA");
            assertThat(MDC.get(LoggedMdcKeys.METHOD_NAME)).isEqualTo("process");

            restoreOuter.run();
            assertThat(MDC.get(LoggedMdcKeys.CLASS_NAME)).isNull();
        }

        @Test
        @DisplayName("does not disturb an unrelated MDC key the application set itself")
        void doesNotDisturbUnrelatedKeys() {
            MDC.put("requestId", "req-123");
            FlowContext context = new FlowContext("trace-abc", 0);

            Runnable restore = MdcPropagation.push(context, "OrderService", "placeOrder", null);
            restore.run();

            assertThat(MDC.get("requestId")).isEqualTo("req-123");
        }
    }

    @Nested
    @DisplayName("callerIp")
    class CallerIp {

        @Test
        @DisplayName("writes CALLER_IP when present")
        void writesCallerIpWhenPresent() {
            FlowContext context = new FlowContext("trace-abc", 0);

            MdcPropagation.push(context, "OrderService", "placeOrder", "203.0.113.5");

            assertThat(MDC.get(LoggedMdcKeys.CALLER_IP)).isEqualTo("203.0.113.5");
        }

        @Test
        @DisplayName("leaves CALLER_IP untouched when null")
        void leavesCallerIpUntouchedWhenNull() {
            FlowContext context = new FlowContext("trace-abc", 0);

            MdcPropagation.push(context, "OrderService", "placeOrder", null);

            assertThat(MDC.get(LoggedMdcKeys.CALLER_IP)).isNull();
        }

        @Test
        @DisplayName("restores CALLER_IP once push() is undone")
        void restoresCallerIpAfterUndo() {
            FlowContext context = new FlowContext("trace-abc", 0);

            Runnable restore = MdcPropagation.push(context, "OrderService", "placeOrder", "203.0.113.5");
            restore.run();

            assertThat(MDC.get(LoggedMdcKeys.CALLER_IP)).isNull();
        }

        @Test
        @DisplayName("restores the outer call's IP once a nested call's push() is undone")
        void restoresOuterCallerIpAfterNestedCall() {
            FlowContext outerContext = new FlowContext("outer-trace", 0);
            Runnable restoreOuter = MdcPropagation.push(outerContext, "ServiceA", "process", "198.51.100.1");

            FlowContext innerContext = new FlowContext("outer-trace", 1);
            Runnable restoreInner = MdcPropagation.push(innerContext, "ServiceB", "doWork", "203.0.113.5");
            restoreInner.run();

            assertThat(MDC.get(LoggedMdcKeys.CALLER_IP)).isEqualTo("198.51.100.1");

            restoreOuter.run();
            assertThat(MDC.get(LoggedMdcKeys.CALLER_IP)).isNull();
        }
    }
}
