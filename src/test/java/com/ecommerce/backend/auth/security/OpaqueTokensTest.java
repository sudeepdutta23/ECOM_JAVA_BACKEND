package com.ecommerce.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpaqueTokensTest {

    private final OpaqueTokens tokens = new OpaqueTokens();

    @Test
    void rawTokenIsUrlSafeAnd43Characters() {
        OpaqueTokens.Generated generated = tokens.generate();

        assertThat(generated.raw()).hasSize(OpaqueTokens.ENCODED_LENGTH).matches(OpaqueTokens.FORMAT_REGEX);
    }

    @Test
    void hashIsLowercaseHexSha256OfTheRawToken() {
        OpaqueTokens.Generated generated = tokens.generate();

        assertThat(generated.hash()).matches("^[0-9a-f]{64}$");
        assertThat(OpaqueTokens.hash(generated.raw())).isEqualTo(generated.hash());
    }

    @Test
    void knownVectorMatchesSha256() {
        // echo -n "abc" | sha256sum
        assertThat(OpaqueTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void tokensAreUnique() {
        Set<String> raws = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            raws.add(tokens.generate().raw());
        }

        assertThat(raws).hasSize(1_000);
    }

    @Test
    void toStringRevealsNeitherTokenNorFullHash() {
        OpaqueTokens.Generated generated = tokens.generate();

        assertThat(generated.toString()).doesNotContain(generated.raw()).doesNotContain(generated.hash());
    }
}
