package com.fayupable.logged.spring.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LogSafe")
class LogSafeTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "user:42",
            "user:j***@example.com",
            "ip:203.0.113.5",
            "2001:db8::1",
            "Windows / Chrome 129",
            "user:çağrı.öğün",
            "user:名前",
            "user:😀",
            "back\\slash",
            "",
            " ~ "
    })
    @DisplayName("returns values without control characters as the same instance")
    void returnsSafeValuesUnchanged(String value) {
        assertThat(LogSafe.singleLine(value)).isSameAs(value);
    }

    @ParameterizedTest(name = "U+{0}")
    @CsvSource({
            "000a, \\n",
            "000d, \\r",
            "0009, \\t",
            "0000, \\u0000",
            "001b, \\u001b",
            "001f, \\u001f",
            "007f, \\u007f",
            "0080, \\u0080",
            "0085, \\u0085",
            "009f, \\u009f",
            "2028, \\u2028",
            "2029, \\u2029"
    })
    @DisplayName("replaces each line-breaking or invisible control character with a visible escape")
    void escapesControlCharacters(String hex, String expected) {
        char c = (char) Integer.parseInt(hex, 16);

        assertThat(LogSafe.singleLine("a" + c + "b")).isEqualTo("a" + expected + "b");
    }

    @Test
    @DisplayName("keeps the characters right next to the escaped range")
    void keepsBoundaryCharacters() {
        String boundaries = "  ‧‪";

        assertThat(LogSafe.singleLine(boundaries)).isSameAs(boundaries);
    }

    @Test
    @DisplayName("turns a forged log line into one visible line")
    void neutralizesForgedLogLine() {
        String forged = "user:eve\r\n2026-10-28 INFO  AuthService - [user:admin] AdminService.deleteAll completed";

        String safe = LogSafe.singleLine(forged);

        assertThat(safe)
                .doesNotContain("\r", "\n")
                .isEqualTo("user:eve\\r\\n2026-10-28 INFO  AuthService - [user:admin] AdminService.deleteAll completed");
    }

    @Test
    @DisplayName("escapes every occurrence, not only the first")
    void escapesEveryOccurrence() {
        assertThat(LogSafe.singleLine("\na\nb\n")).isEqualTo("\\na\\nb\\n");
    }

    @Test
    @DisplayName("returns null for null")
    void returnsNullForNull() {
        assertThat(LogSafe.singleLine(null)).isNull();
    }
}
