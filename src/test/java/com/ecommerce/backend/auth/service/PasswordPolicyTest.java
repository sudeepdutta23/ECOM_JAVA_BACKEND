package com.ecommerce.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.common.response.FieldErrorDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

    private static final String EMAIL = "alice.smith@example.com";
    private final PasswordPolicy policy = new PasswordPolicy();

    @ParameterizedTest
    @ValueSource(strings = {"Correct-Horse-9-Battery", "a long passphrase with spaces", "alice.smith1-x"})
    void acceptsLongUncommonPasswords(String password) {
        assertThatCode(() -> policy.validate("password", password, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void rejectsTooShort() {
        assertRejected("short-pw-1", "between 12 and 128");
    }

    @Test
    void rejectsTooLong() {
        assertRejected("xy".repeat(65), "between 12 and 128");
    }

    @Test
    void countsCharactersNotUtf16Units() {
        // Six emoji are 12 UTF-16 units but only 6 characters, so they must be rejected as too short.
        assertRejected("😀😁😂😃😄😅", "between 12 and 128");
    }

    @Test
    void rejectsTheEmailAddressInAnyCase() {
        assertRejected("Alice.Smith@Example.com", "email");
    }

    @Test
    void rejectsTheEmailLocalPart() {
        assertThatThrownBy(() -> policy.validate("password", "Alexander.Hamilton", "alexander.hamilton@example.com"))
                .isInstanceOfSatisfying(RequestValidationException.class, e ->
                        assertThat(e.getErrors()).anyMatch(err -> err.message().contains("email")));
    }

    @Test
    void rejectsCommonPasswordsCaseInsensitively() {
        assertRejected("Password1234", "too common");
    }

    @Test
    void rejectsSingleRepeatedCharacter() {
        assertRejected("aaaaaaaaaaaaaaa", "repeated");
    }

    @Test
    void errorsAreReportedOnTheGivenField() {
        assertThatThrownBy(() -> policy.validate("newPassword", "Password1234", EMAIL))
                .isInstanceOfSatisfying(RequestValidationException.class, e ->
                        assertThat(e.getErrors()).extracting(FieldErrorDetail::field).containsOnly("newPassword"));
    }

    private void assertRejected(String password, String messagePart) {
        assertThatThrownBy(() -> policy.validate("password", password, EMAIL))
                .isInstanceOfSatisfying(RequestValidationException.class, e ->
                        assertThat(e.getErrors()).anyMatch(err -> err.message().contains(messagePart)));
    }
}
