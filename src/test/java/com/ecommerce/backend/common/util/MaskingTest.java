package com.ecommerce.backend.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingTest {

    @Test
    void keepsFirstCharacterAndDomain() {
        assertThat(Masking.email("alice@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    void handlesMissingOrMalformedInput() {
        assertThat(Masking.email(null)).isEqualTo("<none>");
        assertThat(Masking.email("  ")).isEqualTo("<none>");
        assertThat(Masking.email("no-at-sign")).isEqualTo("***");
        assertThat(Masking.email("@example.com")).isEqualTo("***");
    }
}
