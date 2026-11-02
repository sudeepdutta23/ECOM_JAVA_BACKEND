package com.ecommerce.backend.auth.controller;

import com.ecommerce.backend.auth.dto.AdminUserDetail;
import com.ecommerce.backend.auth.dto.AdminUserSummary;
import com.ecommerce.backend.auth.entity.UserStatus;
import com.ecommerce.backend.auth.dto.SuspendUserRequest;
import com.ecommerce.backend.auth.security.CurrentUser;
import com.ecommerce.backend.auth.service.UserAdminService;
import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.rbac.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account administration (Phase 3): find, inspect, suspend, reactivate, unlock, sign out, erase.
 *
 * <p><b>No {@code Pageable} parameter.</b> Spring can bind {@code ?page&size&sort} straight into
 * one, but then clients choose any sort column, including unindexed ones, and any page size. The
 * page and size are explicit, validated parameters, and the order is fixed (ADR-011).
 */
@RestController
@RequestMapping(path = "/api/v1/admin/users", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Administration: users")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class UserAdminController {

    static final int MAX_PAGE = 10_000;
    static final int MAX_SIZE = 100;

    private final UserAdminService users;

    public UserAdminController(UserAdminService users) {
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "')")
    @Operation(summary = "List and search accounts, newest first",
            description = "Requires user:read. Filters combine with AND. 400 VALIDATION_FAILED for an "
                    + "unknown status, a page below 0, or a size outside 1 to 100.")
    public ApiResponse<PageResponse<AdminUserSummary>> list(
            @Parameter(description = "Exact status") @RequestParam(required = false) UserStatus status,
            @Parameter(description = "Case-insensitive email prefix, e.g. 'ali' finds alice@example.com")
            @RequestParam(required = false) @Size(max = 254) String email,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_SIZE) int size) {
        return ApiResponse.success("Users", users.search(status, email, page, size));
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "')")
    @Operation(summary = "One account with its status history, lockout state, sessions and roles",
            description = "Requires user:read. 404 RESOURCE_NOT_FOUND if unknown.")
    public ApiResponse<AdminUserDetail> get(@PathVariable UUID userId) {
        return ApiResponse.success("User", users.get(userId));
    }

    // ------------------------------------------------------------------ actions

    /*
     * Commands, not a PATCH of the status field: each transition has its own rules, inputs and
     * side effects (see docs/modules/USERS.md). Each returns the updated account.
     */

    @PostMapping(path = "/{userId}/suspend", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    @Operation(summary = "Suspend an active account and end all its sessions",
            description = "Requires user:manage and every permission the account holds. The reason is an "
                    + "internal note, never shown to the owner.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Suspended"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                            description = "FORBIDDEN, USER_SELF_ACTION_DENIED or RBAC_ESCALATION_DENIED"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                            description = "USER_INVALID_STATUS, RBAC_LAST_ADMIN or CONCURRENT_MODIFICATION")})
    public ApiResponse<AdminUserDetail> suspend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
                                                @Valid @RequestBody SuspendUserRequest request) {
        return ApiResponse.success("Account suspended", users.suspend(CurrentUser.id(jwt), userId, request.reason()));
    }

    @PostMapping("/{userId}/reactivate")
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    @Operation(summary = "Reactivate a suspended or deactivated account",
            description = "Requires user:manage. 409 USER_INVALID_STATUS for any other status.")
    public ApiResponse<AdminUserDetail> reactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        return ApiResponse.success("Account reactivated", users.reactivate(CurrentUser.id(jwt), userId));
    }

    @PostMapping("/{userId}/unlock")
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    @Operation(summary = "End a temporary lockout after failed logins (idempotent)", description = "Requires user:manage.")
    public ApiResponse<AdminUserDetail> unlock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        return ApiResponse.success("Account unlocked", users.unlock(CurrentUser.id(jwt), userId));
    }

    @PostMapping("/{userId}/sign-out")
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
    @Operation(summary = "End every session of the account (idempotent)",
            description = "Requires user:manage. Access tokens already issued expire within 15 minutes.")
    public ApiResponse<AdminUserDetail> signOut(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        return ApiResponse.success("Account signed out", users.signOut(CurrentUser.id(jwt), userId));
    }

    @PostMapping("/{userId}/erase")
    @PreAuthorize("hasAuthority('" + Permissions.USER_ERASE + "')")
    @Operation(summary = "Erase an account on its owner's request (irreversible)",
            description = "Requires user:erase and every permission the account holds. Anonymises the account in any "
                    + "status: email and password replaced, sessions, email links and roles deleted. The row and its "
                    + "ID remain, with status ERASED.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Erased"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                            description = "FORBIDDEN, USER_SELF_ACTION_DENIED or RBAC_ESCALATION_DENIED"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                            description = "USER_INVALID_STATUS (already erased), RBAC_LAST_ADMIN or CONCURRENT_MODIFICATION")})
    public ApiResponse<AdminUserDetail> erase(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        return ApiResponse.success("Account erased", users.erase(CurrentUser.id(jwt), userId));
    }
}
