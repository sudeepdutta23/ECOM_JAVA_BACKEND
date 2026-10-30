package com.ecommerce.backend.auth.controller;

import com.ecommerce.backend.auth.dto.EmailRequest;
import com.ecommerce.backend.auth.dto.LoginRequest;
import com.ecommerce.backend.auth.dto.RegisterRequest;
import com.ecommerce.backend.auth.dto.TokenRequest;
import com.ecommerce.backend.auth.dto.TokenResponse;
import com.ecommerce.backend.auth.security.CurrentUser;
import com.ecommerce.backend.auth.security.RefreshTokenCookie;
import com.ecommerce.backend.auth.service.AuthenticationService;
import com.ecommerce.backend.auth.service.ClientInfo;
import com.ecommerce.backend.auth.service.LoginResult;
import com.ecommerce.backend.auth.service.RegistrationService;
import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints. HTTP concerns only: validate the body, call one service method,
 * wrap the result. No business rules live here.
 *
 * <p>{@code consumes = application/json} on every POST: a cross-site HTML form can only send
 * form encodings, and a cross-site script must pass a CORS preflight to send JSON. This is part
 * of the CSRF defence described in ADR-009.
 *
 * <p>Swagger's {@code @ApiResponse} annotation shares its name with our envelope class, so it is
 * written fully qualified.
 */
@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Authentication", description = "Registration, login, tokens and passwords")
public class AuthController {

    static final String REGISTER_MESSAGE =
            "If the email can be registered, a verification link has been sent. Check your inbox.";
    static final String RESEND_MESSAGE =
            "If an unverified account exists for this email, a new verification link has been sent.";

    private final RegistrationService registration;
    private final AuthenticationService authentication;
    private final RefreshTokenCookie refreshCookie;

    public AuthController(RegistrationService registration, AuthenticationService authentication,
                          RefreshTokenCookie refreshCookie) {
        this.registration = registration;
        this.authentication = authentication;
        this.refreshCookie = refreshCookie;
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Sign in with email and password",
            description = "Returns a 15-minute access token in the body and sets the refresh token as an "
                    + "HttpOnly cookie. Unknown email, wrong password and locked account all return the "
                    + "same 401. Five consecutive failures lock the account for 15 minutes.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                            description = "Signed in; Set-Cookie: refresh_token"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_FAILED"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                            description = "AUTH_INVALID_CREDENTIALS"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                            description = "AUTH_EMAIL_NOT_VERIFIED or AUTH_ACCOUNT_DISABLED")})
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request,
                                                            HttpServletRequest httpRequest) {
        LoginResult result = authentication.login(request.email(), request.password(), clientInfo(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.create(result.refreshToken().rawValue()).toString())
                .body(ApiResponse.success("Login successful", TokenResponse.bearer(
                        result.accessToken().value(), result.accessToken().expiresIn())));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Get a new access token",
            description = "Reads the refresh_token cookie, rotates it (the old one stops working) and returns a "
                    + "new access token plus a new cookie. No body and no Authorization header needed. Presenting "
                    + "an already-used refresh token ends the whole session (theft protection).",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                            description = "Rotated; Set-Cookie: refresh_token"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                            description = "AUTH_INVALID_REFRESH_TOKEN: missing, unknown, expired, revoked or reused")})
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken,
            HttpServletRequest httpRequest) {
        LoginResult result = authentication.refresh(refreshToken, clientInfo(httpRequest));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.create(result.refreshToken().rawValue()).toString())
                .body(ApiResponse.success("Token refreshed", TokenResponse.bearer(
                        result.accessToken().value(), result.accessToken().expiresIn())));
    }

    @PostMapping("/logout")
    @Operation(summary = "Sign out of this device",
            description = "Revokes the refresh token in the cookie and deletes the cookie. Always 204, even "
                    + "without a cookie, so it is safe to call twice. Works with an expired access token.",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Signed out"))
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken) {
        authentication.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                .build();
    }

    @PostMapping("/logout-all")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "Sign out of every device",
            description = "Revokes all refresh tokens of the current user. Access tokens already issued remain "
                    + "valid until they expire (at most 15 minutes).",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "All sessions ended"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED")})
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal Jwt jwt) {
        authentication.logoutAll(CurrentUser.id(jwt));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                .build();
    }

    private static ClientInfo clientInfo(HttpServletRequest request) {
        return new ClientInfo(request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
    }

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Register a new account",
            description = "Always answers 202 with the same message, whether or not the email is already "
                    + "registered, so the endpoint cannot be used to discover accounts. The verification "
                    + "link is emailed.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Accepted"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                            description = "VALIDATION_FAILED or MALFORMED_REQUEST")})
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        registration.register(request.email(), request.password());
        return ApiResponse.success(REGISTER_MESSAGE);
    }

    @PostMapping(path = "/email/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Verify an email address",
            description = "Consumes the one-time token from the verification email and activates the account.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Verified"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                            description = "VALIDATION_FAILED or AUTH_INVALID_ACCOUNT_TOKEN")})
    public ApiResponse<Void> verifyEmail(@Valid @RequestBody TokenRequest request) {
        registration.verifyEmail(request.token());
        return ApiResponse.success("Email verified. You can now sign in.");
    }

    @PostMapping(path = "/email/resend-verification", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Resend the verification email",
            description = "Always answers 202 with the same message. Earlier links stop working.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Accepted"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_FAILED")})
    public ApiResponse<Void> resendVerification(@Valid @RequestBody EmailRequest request) {
        registration.resendVerification(request.email());
        return ApiResponse.success(RESEND_MESSAGE);
    }
}
