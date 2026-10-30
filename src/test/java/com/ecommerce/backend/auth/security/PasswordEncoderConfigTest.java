package com.ecommerce.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordEncoderConfigTest {

    private final PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder(2, java.time.Duration.ofSeconds(5));

    @Test
    void newHashesUseArgon2idWithOwaspParameters() {
        String hash = encoder.encode("Correct-Horse-9-Battery");

        assertThat(hash).startsWith("{argon2}$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(hash).hasSizeLessThanOrEqualTo(255); // fits users.password_hash
    }

    @Test
    void matchesOnlyTheRightPassword() {
        String hash = encoder.encode("Correct-Horse-9-Battery");

        assertThat(encoder.matches("Correct-Horse-9-Battery", hash)).isTrue();
        assertThat(encoder.matches("correct-horse-9-battery", hash)).isFalse();
    }

    @Test
    void samePasswordProducesDifferentHashesBecauseOfRandomSalt() {
        assertThat(encoder.encode("same-password-123")).isNotEqualTo(encoder.encode("same-password-123"));
    }

    @Test
    void legacyBcryptHashesStillVerifyAndAreFlaggedForUpgrade() {
        String legacy = "{bcrypt}" + new BCryptPasswordEncoder(4).encode("old-system-password");

        assertThat(encoder.matches("old-system-password", legacy)).isTrue();
        assertThat(encoder.upgradeEncoding(legacy)).isTrue();
        assertThat(encoder.upgradeEncoding(encoder.encode("x-new-password-1"))).isFalse();
    }
}
