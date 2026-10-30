package com.ecommerce.backend.auth.repository;

import com.ecommerce.backend.AbstractIntegrationTest;
import com.ecommerce.backend.auth.entity.User;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared helpers for repository tests.
 *
 * <p>These tests are deliberately NOT {@code @Transactional}. Concurrency and locking only
 * behave realistically when each operation commits in its own transaction, exactly as in
 * production. Instead of rollback, every test uses unique emails under {@code @repo.test}
 * and the rows are deleted afterwards (tokens go with them via ON DELETE CASCADE).
 */
abstract class AbstractAuthRepositoryIT extends AbstractIntegrationTest {

    static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    UserRepository users;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void deleteTestUsers() {
        jdbc.update("DELETE FROM users WHERE email LIKE '%@repo.test'");
    }

    static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@repo.test";
    }

    static String randomHash() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    User savedUser() {
        return users.save(User.register(uniqueEmail(), "{argon2}test-hash", NOW));
    }
}
