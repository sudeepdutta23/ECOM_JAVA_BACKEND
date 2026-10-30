package com.ecommerce.backend.auth.controller;

import com.ecommerce.backend.auth.dto.MeResponse;
import com.ecommerce.backend.auth.dto.PasswordConfirmationRequest;
import com.ecommerce.backend.auth.security.CurrentUser;
import com.ecommerce.backend.auth.security.RefreshTokenCookie;
import com.ecommerce.backend.auth.service.AccountService;
import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's own account. Every endpoint here requires an access token. */
@RestController
@RequestMapping(path = "/api/v1/auth/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Authentication")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AccountController {

    private final AccountService accounts;
    private final RefreshTokenCookie refreshCookie;

    public AccountController(AccountService accounts, RefreshTokenCookie refreshCookie) {
        this.accounts = accounts;
        this.refreshCookie = refreshCookie;
    }

    @GetMapping
    @Operation(summary = "Current account",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                            description = "UNAUTHORIZED or AUTH_TOKEN_EXPIRED")})
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success("Current user", MeResponse.from(accounts.currentUser(CurrentUser.id(jwt))));
    }

    @PostMapping(path = "/deactivate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Deactivate my account",
            description = "Requires the current password. Signs out every device. Only an administrator can "
                    + "reactivate the account.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deactivated"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                            description = "VALIDATION_FAILED or AUTH_INVALID_CURRENT_PASSWORD"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED")})
    public ResponseEntity<Void> deactivate(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody PasswordConfirmationRequest request) {
        accounts.deactivate(CurrentUser.id(jwt), request.password());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie.clear().toString())
                .build();
    }
}
