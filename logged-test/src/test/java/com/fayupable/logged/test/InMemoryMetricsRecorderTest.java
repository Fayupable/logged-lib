package com.fayupable.logged.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

@DisplayName("InMemoryMetricsRecorder")
class InMemoryMetricsRecorderTest {

    @Nested
    @DisplayName("record")
    class Record {

        @Test
        @DisplayName("records calls in recording order")
        void recordsCallsInOrder() {
            InMemoryMetricsRecorder recorder = new InMemoryMetricsRecorder();

            recorder.record("SomeClass", "first", 1_000_000L, true, null);
            recorder.record("SomeClass", "second", 2_000_000L, false, "IllegalStateException");

            assertThat(recorder.metrics())
                    .extracting(RecordedMetric::methodName)
                    .containsExactly("first", "second");
        }

        @Test
        @DisplayName("counts recorded calls")
        void countsRecordedCalls() {
            InMemoryMetricsRecorder recorder = new InMemoryMetricsRecorder();

            recorder.record("SomeClass", "first", 1_000_000L, true, null);
            recorder.record("SomeClass", "second", 2_000_000L, true, null);

            assertThat(recorder.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("preserves every reported field")
        void preservesReportedFields() {
            InMemoryMetricsRecorder recorder = new InMemoryMetricsRecorder();

            recorder.record("SomeClass", "someMethod", 1_500_000L, false, "IllegalStateException");

            assertThat(recorder.lastMetric()).isEqualTo(
                    new RecordedMetric("SomeClass", "someMethod", 1_500_000L, false, "IllegalStateException")
            );
        }
    }

    @Nested
    @DisplayName("lastMetric")
    class LastMetric {

        @Test
        @DisplayName("throws when nothing has been recorded yet")
        void throwsWhenEmpty() {
            InMemoryMetricsRecorder recorder = new InMemoryMetricsRecorder();

            assertThatIllegalStateException().isThrownBy(recorder::lastMetric);
        }
    }

    @Nested
    @DisplayName("clear")
    class Clear {

        @Test
        @DisplayName("discards previously recorded calls")
        void discardsRecordedCalls() {
            InMemoryMetricsRecorder recorder = new InMemoryMetricsRecorder();
            recorder.record("SomeClass", "someMethod", 1_000_000L, true, null);

            recorder.clear();

            assertThat(recorder.count()).isZero();
        }
    }
}
