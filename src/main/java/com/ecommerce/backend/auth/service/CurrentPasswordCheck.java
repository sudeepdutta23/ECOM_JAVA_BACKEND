package com.ecommerce.backend.auth.service;

import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Re-confirms the password before a sensitive action (change password, deactivate account).
 *
 * <p><b>Why ask again when the user is already signed in?</b> An access token can be stolen, or a
 * laptop left unlocked. Requiring the password for irreversible actions limits what a hijacked
 * session can do. Wrong answers count toward the same lockout as login.
 */
@Component
class CurrentPasswordCheck {

    private static final Logger log = LoggerFactory.getLogger(CurrentPasswordCheck.class);

    private final PasswordEncoder passwordEncoder;
    private final FailedLoginRecorder failedLogins;

    CurrentPasswordCheck(PasswordEncoder passwordEncoder, FailedLoginRecorder failedLogins) {
        this.passwordEncoder = passwordEncoder;
        this.failedLogins = failedLogins;
    }

    void verify(User user, String password, Instant now) {
        if (user.isLockedAt(now)) {
            log.info("Password confirmation rejected: reason=LOCKED, userId={}", user.getId());
            throw incorrect();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            failedLogins.record(user, now);
            log.info("Password confirmation failed: userId={}", user.getId());
            throw incorrect();
        }
    }

    private static BusinessException incorrect() {
        return new BusinessException(ErrorCode.AUTH_INVALID_CURRENT_PASSWORD, "The current password is incorrect.");
    }
}
