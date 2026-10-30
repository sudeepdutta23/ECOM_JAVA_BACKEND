package com.ecommerce.backend.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

class RefreshTokenCookieTest {

    @Test
    void cookieHasAllProtectiveAttributes() {
        ResponseCookie cookie = new RefreshTokenCookie(Duration.ofDays(7), true).create("raw-value");

        assertThat(cookie.toString())
                .startsWith("refresh_token=raw-value")
                .contains("Path=/api/v1/auth")
                .contains("Max-Age=604800")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict");
    }

    @Test
    void secureFlagCanBeDisabledForLocalHttp() {
        assertThat(new RefreshTokenCookie(Duration.ofDays(7), false).create("v").isSecure()).isFalse();
    }

    @Test
    void clearExpiresTheCookieImmediately() {
        ResponseCookie cleared = new RefreshTokenCookie(Duration.ofDays(7), true).clear();

        assertThat(cleared.getValue()).isEmpty();
        assertThat(cleared.getMaxAge()).isZero();
        assertThat(cleared.getPath()).isEqualTo("/api/v1/auth"); // must match, or the browser keeps the old one
    }
}
