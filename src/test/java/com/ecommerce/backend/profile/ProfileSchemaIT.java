package com.ecommerce.backend.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.backend.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V10 constraints and the cascade, straight against the database: the last line of defence. */
class ProfileSchemaIT extends AbstractIntegrationTest {

    private static final String DOMAIN = "@profile-schema.test";

    @Autowired
    private JdbcTemplate jdbc;

    private UUID user;

    @BeforeEach
    void setUp() {
        user = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, status, password_changed_at)
                VALUES (?, ?, '{argon2}x', 'PENDING_VERIFICATION', now())""", user, "u-" + user + DOMAIN);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE '%" + DOMAIN + "'");
    }

    private void insert(String column, String value) {
        jdbc.update("INSERT INTO user_profiles (user_id, " + column + ") VALUES (?, ?)", user, value);
    }

    private long profiles() {
        return jdbc.queryForObject("SELECT count(*) FROM user_profiles WHERE user_id = ?", Long.class, user);
    }

    @Test
    void anEmptyProfileIsValidAndStartsAtVersionZero() {
        jdbc.update("INSERT INTO user_profiles (user_id) VALUES (?)", user);

        assertThat(jdbc.queryForObject("SELECT version FROM user_profiles WHERE user_id = ?", Long.class, user)).isZero();
    }

    @Test
    void anAccountHasAtMostOneProfile() {
        jdbc.update("INSERT INTO user_profiles (user_id) VALUES (?)", user);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_profiles (user_id) VALUES (?)", user))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aProfileNeedsAnExistingAccount() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_profiles (user_id) VALUES (?)", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void phoneNumbersMustBeE164() {
        for (String invalid : List.of("0301234567", "+0301234567", "+49 301234567", "+49-30-1234567", "+12345",
                "+1234567890123456")) {
            assertThatThrownBy(() -> insert("phone_number", invalid)).as(invalid)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        insert("phone_number", "+493012345678");
        assertThat(profiles()).isOne();
    }

    @Test
    void localesMustBeLanguageTags() {
        for (String invalid : List.of("EN", "en_GB", "en-gb", "english", "")) {
            assertThatThrownBy(() -> insert("preferred_locale", invalid)).as(invalid)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        insert("preferred_locale", "en-GB");
        assertThat(profiles()).isOne();
    }

    @Test
    void namesAndTimeZonesAreTrimmedAndNeverEmpty() {
        for (String column : List.of("given_name", "family_name", "time_zone")) {
            for (String invalid : List.of("", " Ada", "Ada ")) {
                assertThatThrownBy(() -> insert(column, invalid)).as(column + " '" + invalid + "'")
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
        }
    }

    @Test
    void deletingTheAccountDeletesItsProfileAndAddresses() {
        insert("given_name", "Ada");
        address(false, false);

        jdbc.update("DELETE FROM users WHERE id = ?", user);

        assertThat(profiles()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_addresses WHERE user_id = ?", Long.class, user)).isZero();
    }

    // ------------------------------------------------------------------ addresses (V11)

    private UUID address(boolean defaultShipping, boolean defaultBilling) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_addresses (id, user_id, recipient_name, line1, city, postal_code, country_code,
                                            default_shipping, default_billing)
                VALUES (?, ?, 'Ada', 'Street 1', 'Berlin', '10117', 'DE', ?, ?)""",
                id, user, defaultShipping, defaultBilling);
        return id;
    }

    /** A valid address, then one column set to {@code value}; the UPDATE is what must fail. */
    private void addressWith(String column, String value) {
        jdbc.update("INSERT INTO user_addresses (id, user_id, recipient_name, line1, city, country_code) "
                + "VALUES (?, ?, 'Ada', 'Street 1', 'Berlin', 'DE')", UUID.randomUUID(), user);
        jdbc.update("UPDATE user_addresses SET " + column + " = ? WHERE user_id = ?", value, user);
    }

    @Test
    void atMostOneDefaultOfEachKindPerAccountButAnyNumberOfOthers() {
        address(true, false);
        address(false, true);
        address(false, false);
        address(false, false);

        assertThatThrownBy(() -> address(true, false)).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> address(false, true)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void anotherAccountHasItsOwnDefaults() {
        address(true, true);
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, status, password_changed_at) "
                + "VALUES (?, ?, '{argon2}x', 'PENDING_VERIFICATION', now())", other, "u-" + other + DOMAIN);

        jdbc.update("INSERT INTO user_addresses (id, user_id, recipient_name, line1, city, country_code, default_shipping, "
                + "default_billing) VALUES (?, ?, 'Bob', 'Street 2', 'Paris', 'FR', true, true)", UUID.randomUUID(), other);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_addresses WHERE default_shipping AND user_id IN (?, ?)",
                Long.class, user, other)).isEqualTo(2);
    }

    @Test
    void addressFieldsAreCheckedForFormat() {
        List<String[]> invalid = List.of(
                new String[] {"country_code", "de"}, new String[] {"country_code", "D"},
                new String[] {"postal_code", "10117 "}, new String[] {"postal_code", "sw1a 1aa"},
                new String[] {"postal_code", "-10117"}, new String[] {"phone_number", "030123456"},
                new String[] {"recipient_name", " Ada"}, new String[] {"line2", ""}, new String[] {"label", ""});
        for (String[] column : invalid) {
            assertThatThrownBy(() -> addressWith(column[0], column[1])).as(column[0] + " '" + column[1] + "'")
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("DELETE FROM user_addresses WHERE user_id = ?", user);
        }
    }
}
