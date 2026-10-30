package com.ecommerce.backend.auth.service;

import java.time.Duration;

/** The production defaults from application.yml, for unit tests that build services by hand. */
final class TestAuthProperties {

    static final Duration EMAIL_VERIFICATION_TTL = Duration.ofHours(24);
    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);
    static final int MAX_FAILED_LOGINS = 5;
    static final Duration LOCKOUT = Duration.ofMinutes(15);
    static final Duration PASSWORD_RESET_TTL = Duration.ofMinutes(30);

    private TestAuthProperties() {
    }

    /** No response-time floor, so unit tests stay fast. */
    static AuthProperties defaults() {
        return withFailedLoginFloor(Duration.ZERO);
    }

    static AuthProperties withFailedLoginFloor(Duration floor) {
        return new AuthProperties("http://localhost:3000", EMAIL_VERIFICATION_TTL, REFRESH_TOKEN_TTL,
                MAX_FAILED_LOGINS, LOCKOUT, PASSWORD_RESET_TTL, floor);
    }
}
