package com.ecommerce.backend.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UserSearchTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "alice     | alice",
            "a_b       | a\\_b",
            "100%      | 100\\%",
            "back\\sl  | back\\\\sl",
            "%_\\      | \\%\\_\\\\"
    })
    void wildcardsAndTheEscapeCharacterAreEscaped(String input, String expected) {
        assertThat(UserSearch.escapeLike(input)).isEqualTo(expected);
    }
}
