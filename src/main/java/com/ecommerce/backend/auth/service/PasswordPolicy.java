package com.ecommerce.backend.auth.service;

import com.ecommerce.backend.common.exception.RequestValidationException;
import com.ecommerce.backend.common.response.FieldErrorDetail;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Password rules following NIST SP 800-63B: length matters, composition rules do not.
 *
 * <ul>
 *   <li>12 to 128 characters. The upper bound also caps the work done by the password hasher,
 *       so huge inputs cannot be used to burn CPU.</li>
 *   <li>Not the email address or its local part.</li>
 *   <li>Not a known common password.</li>
 *   <li>Not a single repeated character.</li>
 * </ul>
 *
 * <p>No "must contain a symbol" rules: they push users to predictable patterns like
 * "Password1!". A production upgrade is checking against breached passwords with the
 * HaveIBeenPwned k-anonymity API.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    private final Set<String> commonPasswords;

    public PasswordPolicy() {
        this.commonPasswords = loadCommonPasswords();
    }

    public void validate(String field, String password, String normalizedEmail) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        int length = password == null ? 0 : password.codePointCount(0, password.length());

        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            errors.add(new FieldErrorDetail(field,
                    "must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters"));
        } else {
            String lower = password.toLowerCase(Locale.ROOT);
            String localPart = normalizedEmail == null ? "" : normalizedEmail.substring(0, Math.max(0, normalizedEmail.indexOf('@')));
            if (lower.equals(normalizedEmail) || (!localPart.isEmpty() && lower.equals(localPart))) {
                errors.add(new FieldErrorDetail(field, "must not be your email address"));
            }
            if (commonPasswords.contains(lower)) {
                errors.add(new FieldErrorDetail(field, "is too common; choose something less predictable"));
            }
            if (password.codePoints().distinct().count() == 1) {
                errors.add(new FieldErrorDetail(field, "must not be a single repeated character"));
            }
        }

        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
    }

    private static Set<String> loadCommonPasswords() {
        try (InputStream in = new ClassPathResource("auth/common-passwords.txt").getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load auth/common-passwords.txt", e);
        }
    }
}
