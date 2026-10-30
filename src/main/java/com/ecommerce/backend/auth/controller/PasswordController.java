package com.ecommerce.backend.auth.controller;

import com.ecommerce.backend.auth.dto.ChangePasswordRequest;
import com.ecommerce.backend.auth.dto.EmailRequest;
import com.ecommerce.backend.auth.dto.ResetPasswordRequest;
import com.ecommerce.backend.auth.dto.TokenResponse;
import com.ecommerce.backend.auth.security.CurrentUser;
import com.ecommerce.backend.auth.security.RefreshTokenCookie;
import com.ecommerce.backend.auth.service.ClientInfo;
import com.ecommerce.backend.auth.service.LoginResult;
import com.ecommerce.backend.auth.service.PasswordService;
import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Password change (signed in) and forgot/reset (signed out). */
@RestController
@RequestMapping(path = "/api/v1/auth/password", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Authentication")
public class PasswordController {

    static final String FORGOT_MESSAGE =
            "If an active account exists for this email, a password reset link has been sent.";

    private final PasswordService passwords;
    private final RefreshTokenCookie refreshCookie;

    public PasswordController(PasswordService passwords, RefreshTokenCookie refreshCookie) {
        this.passwords = passwords;
        this.refreshCookie = refreshCookie;
    }

    @PostMapping(path = "/change", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "Change password",
            description = "Requires the current password. Signs out every device, then returns a new session "
                    + "for this one. Wrong current passwords count toward the account lockout.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                            description = "Changed; new access token and Set-Cookie: refresh_token"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                            description = "VALIDATION_FAILED or AUTH_INVALID_CURRENT_PASSWORD"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED")})
    public ResponseEntity<ApiResponse<TokenResponse>> change(@AuthenticationPrincipal Jwt jwt,
                                                             @Valid @RequestBody ChangePasswordRequest request,
                                                             HttpServletRequest httpRequest) {
        LoginResult result = passwords.changePassword(CurrentUser.id(jwt), request.currentPassword(),
                request.newPassword(),
                new ClientInfo(httpRequest.getRemoteAddr(), httpRequest.getHeader(HttpHeaders.USER_AGENT)));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.create(result.refreshToken().rawValue()).toString())
                .body(ApiResponse.success("Password changed. Other devices were signed out.",
                        TokenResponse.bearer(result.accessToken().value(), result.accessToken().expiresIn())));
    }

    @PostMapping(path = "/forgot", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Request a password reset link",
            description = "Always 202 with the same message, so it cannot reveal which emails have accounts.",
            responses = @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Accepted"))
    public ApiResponse<Void> forgot(@Valid @RequestBody EmailRequest request) {
        passwords.forgotPassword(request.email());
        return ApiResponse.success(FORGOT_MESSAGE);
    }

    @PostMapping(path = "/reset", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Set a new password with a reset link",
            description = "Consumes the one-time token, sets the password, clears any lockout and signs out every "
                    + "device. The user then signs in with the new password.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Password reset"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                            description = "VALIDATION_FAILED or AUTH_INVALID_ACCOUNT_TOKEN")})
    public ResponseEntity<Void> reset(@Valid @RequestBody ResetPasswordRequest request) {
        passwords.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
