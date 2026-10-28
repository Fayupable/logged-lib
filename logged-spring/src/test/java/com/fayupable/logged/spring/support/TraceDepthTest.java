package com.fayupable.logged.spring.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TraceDepth")
class TraceDepthTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "0, 0",
            "1, 1",
            "3, 3",
            "' 7 ', 7",
            "999, 999",
            "1000, 1000",
            "1001, 1000",
            "2147483647, 1000",
            "-1, 0",
            "-2147483648, 0"
    })
    @DisplayName("keeps a depth within 0..MAX_DEPTH and clamps anything outside it")
    void clampsToRange(String raw, int expected) {
        assertThat(TraceDepth.parse(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "abc", "1.5", "2147483648", "3\r\nFAKE", "0x10"})
    @DisplayName("starts a new chain at 0 when the depth is missing or not a whole number")
    void defaultsToZero(String raw) {
        assertThat(TraceDepth.parse(raw)).isZero();
    }
}
