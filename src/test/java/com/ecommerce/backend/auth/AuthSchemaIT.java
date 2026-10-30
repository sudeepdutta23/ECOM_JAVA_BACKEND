package com.ecommerce.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.backend.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the V2 constraints protect the data even if application code has a bug.
 *
 * <p>Each test runs in a transaction that is rolled back afterwards, so tests never see each
 * other's rows. Plain SQL is used on purpose: these tests check the database, not JPA.
 */
@Transactional
class AuthSchemaIT extends AbstractIntegrationTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ migration

    @Test
    void migrationV2IsApplied() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = true",
                Integer.class);
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                        + "AND table_name IN ('users','refresh_tokens','account_tokens') ORDER BY table_name",
                String.class);

        assertThat(applied).isEqualTo(1);
        assertThat(tables).containsExactly("account_tokens", "refresh_tokens", "users");
    }

    // ------------------------------------------------------------------ users

    @Test
    void validUserIsAccepted() {
        UUID id = insertUser("alice@example.com", "PENDING_VERIFICATION", null);

        Integer failed = jdbc.queryForObject(
                "SELECT failed_login_count FROM users WHERE id = ?", Integer.class, id);
        assertThat(failed).isZero();
    }

    @Test
    void duplicateEmailIsRejected() {
        insertUser("bob@example.com", "PENDING_VERIFICATION", null);

        assertThatThrownBy(() -> insertUser("bob@example.com", "PENDING_VERIFICATION", null))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_users_email");
    }

    @Test
    void nonNormalizedEmailIsRejected() {
        assertThatThrownBy(() -> insertUser("Bob@Example.com", "PENDING_VERIFICATION", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_email_normalized");
    }

    @Test
    void unknownStatusIsRejected() {
        assertThatThrownBy(() -> insertUser("carol@example.com", "SUPERUSER", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_status");
    }

    @Test
    void activeUserMustHaveVerifiedEmail() {
        assertThatThrownBy(() -> insertUser("dave@example.com", "ACTIVE", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_active_requires_verified");
    }

    @Test
    void negativeFailedLoginCountIsRejected() {
        UUID id = insertUser("erin@example.com", "PENDING_VERIFICATION", null);

        assertThatThrownBy(() -> jdbc.update("UPDATE users SET failed_login_count = -1 WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_failed_login_count");
    }

    // ------------------------------------------------------------------ refresh_tokens

    @Test
    void refreshTokenHashMustBeLowercaseHexSha256() {
        UUID userId = insertActiveUser("frank@example.com");

        assertThatThrownBy(() -> insertRefreshToken(userId, "not-a-hash"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_refresh_tokens_hash_format");
    }

    @Test
    void duplicateRefreshTokenHashIsRejected() {
        UUID userId = insertActiveUser("grace@example.com");
        insertRefreshToken(userId, HASH_A);

        assertThatThrownBy(() -> insertRefreshToken(userId, HASH_A))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void revokedAtAndReasonMustBeSetTogether() {
        UUID userId = insertActiveUser("heidi@example.com");
        UUID tokenId = insertRefreshToken(userId, HASH_A);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE refresh_tokens SET revoked_at = now() WHERE id = ?", tokenId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_refresh_tokens_revocation_consistent");
    }

    @Test
    void refreshTokenForUnknownUserIsRejected() {
        assertThatThrownBy(() -> insertRefreshToken(UUID.randomUUID(), HASH_A))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_refresh_tokens_user");
    }

    // ------------------------------------------------------------------ account_tokens

    @Test
    void unknownAccountTokenPurposeIsRejected() {
        UUID userId = insertActiveUser("ivan@example.com");

        assertThatThrownBy(() -> insertAccountToken(userId, "MAGIC_LINK", HASH_A))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_account_tokens_purpose");
    }

    // ------------------------------------------------------------------ cascade

    @Test
    void deletingUserDeletesTheirTokens() {
        UUID userId = insertActiveUser("judy@example.com");
        insertRefreshToken(userId, HASH_A);
        insertAccountToken(userId, "PASSWORD_RESET", HASH_B);

        jdbc.update("DELETE FROM users WHERE id = ?", userId);

        assertThat(count("refresh_tokens", userId)).isZero();
        assertThat(count("account_tokens", userId)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private UUID insertUser(String email, String status, Instant verifiedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, status, email_verified_at, password_changed_at)
                VALUES (?, ?, '{argon2}dummy', ?, ?, now())
                """, id, email, status, verifiedAt == null ? null : Timestamp.from(verifiedAt));
        return id;
    }

    private UUID insertActiveUser(String email) {
        return insertUser(email, "ACTIVE", Instant.now());
    }

    private UUID insertRefreshToken(UUID userId, String hash) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, userId, UUID.randomUUID(), hash,
                Timestamp.from(now), Timestamp.from(now.plus(7, ChronoUnit.DAYS)));
        return id;
    }

    private void insertAccountToken(UUID userId, String purpose, String hash) {
        jdbc.update("""
                INSERT INTO account_tokens (id, user_id, purpose, token_hash, expires_at)
                VALUES (?, ?, ?, ?, now() + interval '1 hour')
                """, UUID.randomUUID(), userId, purpose, hash);
    }

    private int count(String table, UUID userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }
}
