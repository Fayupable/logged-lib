package com.fayupable.logged.spring.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EmailMasker")
class EmailMaskerTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "jane.doe@example.com, j***@example.com",
            "a@example.com, a***@example.com",
            "j@x, j***@x",
            "Jane.Doe@Sub.Example.CO.UK, J***@Sub.Example.CO.UK"
    })
    @DisplayName("masks e-mail addresses down to the first character and the domain")
    void masksEmailAddresses(String input, String expected) {
        assertThat(EmailMasker.mask(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"42", "jane", "", "@example.com", "jane@", "@", "a@b@c.com", "jane@@example.com"})
    @DisplayName("returns values that are not a single local@domain pair unchanged")
    void leavesNonEmailValuesUnchanged(String input) {
        assertThat(EmailMasker.mask(input)).isEqualTo(input);
    }

    @Test
    @DisplayName("returns null for null")
    void returnsNullForNull() {
        assertThat(EmailMasker.mask(null)).isNull();
    }
}
