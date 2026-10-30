package com.ecommerce.backend.auth.security;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing (ADR-007).
 *
 * <p>{@link DelegatingPasswordEncoder} stores the algorithm as a prefix, e.g.
 * {@code {argon2}$argon2id$v=19$m=19456,t=2,p=1$...}. New hashes always use Argon2id. Existing
 * hashes with another prefix (say, BCrypt from an imported legacy system) still verify, and
 * {@link PasswordEncoder#upgradeEncoding} tells the login flow to re-hash them.
 *
 * <p>The whole encoder is wrapped in a {@link BoundedPasswordEncoder} that caps concurrent hashes.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfig {

    /** OWASP Password Storage Cheat Sheet minimum for Argon2id. */
    static final int SALT_LENGTH_BYTES = 16;
    static final int HASH_LENGTH_BYTES = 32;
    static final int PARALLELISM = 1;
    static final int MEMORY_KIB = 19 * 1024;
    static final int ITERATIONS = 2;

    /**
     * @param maxConcurrent concurrent hashes allowed; 0 means one per CPU core. Worst-case memory
     *                      is this number times 19 MiB.
     */
    @Bean
    public PasswordEncoder passwordEncoder(
            @Value("${app.security.password-hashing.max-concurrent:0}") int maxConcurrent,
            @Value("${app.security.password-hashing.max-wait:5s}") Duration maxWait) {
        int permits = maxConcurrent > 0 ? maxConcurrent : Runtime.getRuntime().availableProcessors();
        return new BoundedPasswordEncoder(unbounded(), permits, maxWait);
    }

    static PasswordEncoder unbounded() {
        String defaultId = "argon2";
        Map<String, PasswordEncoder> encoders = Map.of(
                defaultId, new Argon2PasswordEncoder(SALT_LENGTH_BYTES, HASH_LENGTH_BYTES, PARALLELISM,
                        MEMORY_KIB, ITERATIONS),
                "bcrypt", new BCryptPasswordEncoder(12));
        return new DelegatingPasswordEncoder(defaultId, encoders);
    }
}
