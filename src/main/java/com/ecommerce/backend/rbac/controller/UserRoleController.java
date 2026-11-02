package com.ecommerce.backend.rbac.controller;

import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.rbac.Permissions;
import com.ecommerce.backend.rbac.dto.UserRoleResponse;
import com.ecommerce.backend.rbac.service.RoleAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who holds which role.
 *
 * <p>"User X holds role Y" is a resource identified by both IDs, so granting is a PUT and revoking
 * a DELETE on it: both idempotent, both without a body. Changes reach the user's access token at
 * their next refresh (at most 15 minutes).
 */
@RestController
@RequestMapping(path = "/api/v1/admin/users/{userId}/roles", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Administration: roles and permissions")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class UserRoleController {

    private final RoleAssignmentService assignments;

    public UserRoleController(RoleAssignmentService assignments) {
        this.assignments = assignments;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_READ + "')")
    @Operation(summary = "List a user's roles", description = "Requires role:read. 404 if the user does not exist.")
    public ApiResponse<List<UserRoleResponse>> list(@PathVariable UUID userId) {
        return ApiResponse.success("User roles",
                assignments.rolesOf(userId).stream().map(UserRoleResponse::from).toList());
    }

    @PutMapping("/{roleId}")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_ASSIGN + "')")
    @Operation(summary = "Grant a role to a user (idempotent)",
            description = "Requires role:assign, and you must hold every permission of the role.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Held now"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                            description = "FORBIDDEN, RBAC_ESCALATION_DENIED or RBAC_SELF_MODIFICATION_DENIED"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                            description = "RESOURCE_NOT_FOUND (user or role)")})
    public ResponseEntity<Void> grant(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
                                      @PathVariable UUID roleId) {
        assignments.grant(callerId(jwt), userId, roleId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{roleId}")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_ASSIGN + "')")
    @Operation(summary = "Revoke a role from a user (idempotent)",
            description = "Requires role:assign, and you must hold every permission of the role.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Not held now"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                            description = "FORBIDDEN, RBAC_ESCALATION_DENIED or RBAC_SELF_MODIFICATION_DENIED"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                            description = "RESOURCE_NOT_FOUND (user or role)"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                            description = "RBAC_LAST_ADMIN or RBAC_SYSTEM_ROLE (CUSTOMER)")})
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
                                       @PathVariable UUID roleId) {
        assignments.revoke(callerId(jwt), userId, roleId);
        return ResponseEntity.noContent().build();
    }

    /** rbac must not import auth classes, so the token subject is read directly here. */
    private static UUID callerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
