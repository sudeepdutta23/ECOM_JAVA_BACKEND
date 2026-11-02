package com.ecommerce.backend.rbac.controller;

import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.rbac.Permissions;
import com.ecommerce.backend.rbac.dto.CreateRoleRequest;
import com.ecommerce.backend.rbac.dto.RoleResponse;
import com.ecommerce.backend.rbac.dto.UpdateRoleRequest;
import com.ecommerce.backend.rbac.entity.Role;
import com.ecommerce.backend.rbac.service.RoleManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role management for administrators.
 *
 * <p>Two layers of authorization: {@code @PreAuthorize} decides whether you may use the endpoint
 * at all; the service decides whether you may touch THIS role (escalation and system-role rules).
 */
@RestController
@RequestMapping(path = "/api/v1/admin/roles", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Administration: roles and permissions")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class RoleController {

    private final RoleManagementService roles;

    public RoleController(RoleManagementService roles) {
        this.roles = roles;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_READ + "')")
    @Operation(summary = "List roles with their permissions", description = "Requires role:read.")
    public ApiResponse<List<RoleResponse>> list() {
        return ApiResponse.success("Roles", roles.list().stream().map(RoleResponse::from).toList());
    }

    @GetMapping("/{roleId}")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_READ + "')")
    @Operation(summary = "Get one role", description = "Requires role:read. 404 RESOURCE_NOT_FOUND if unknown.")
    public ApiResponse<RoleResponse> get(@PathVariable UUID roleId) {
        return ApiResponse.success("Role", RoleResponse.from(roles.get(roleId)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_WRITE + "')")
    @Operation(summary = "Create a custom role",
            description = "Requires role:write, and you must hold every permission you put in the role.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Created; Location header"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_FAILED"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                            description = "FORBIDDEN or RBAC_ESCALATION_DENIED"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_RESOURCE")})
    public ResponseEntity<ApiResponse<RoleResponse>> create(@AuthenticationPrincipal Jwt jwt,
                                                            @Valid @RequestBody CreateRoleRequest request) {
        Role role = roles.create(callerId(jwt), request.code(),
                new RoleManagementService.RoleCommand(request.name(), request.description(), request.permissions()));
        return ResponseEntity.created(URI.create("/api/v1/admin/roles/" + role.getId()))
                .body(ApiResponse.success("Role created", RoleResponse.from(role)));
    }

    @PutMapping(path = "/{roleId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_WRITE + "')")
    @Operation(summary = "Replace a custom role's name, description and permissions",
            description = "Requires role:write. Send the version you read; a stale version returns 409. "
                    + "Holders get the new permissions at their next token refresh.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                            description = "FORBIDDEN or RBAC_ESCALATION_DENIED"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                            description = "CONCURRENT_MODIFICATION or RBAC_SYSTEM_ROLE")})
    public ApiResponse<RoleResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roleId,
                                            @Valid @RequestBody UpdateRoleRequest request) {
        Role role = roles.update(callerId(jwt), roleId,
                new RoleManagementService.RoleCommand(request.name(), request.description(), request.permissions()),
                request.version());
        return ApiResponse.success("Role updated", RoleResponse.from(role));
    }

    @DeleteMapping("/{roleId}")
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_WRITE + "')")
    @Operation(summary = "Delete a custom role",
            description = "Requires role:write. The role must not be assigned to anyone.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Deleted"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                            description = "RBAC_ROLE_IN_USE or RBAC_SYSTEM_ROLE")})
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roleId) {
        roles.delete(callerId(jwt), roleId);
        return ResponseEntity.noContent().build();
    }

    /** rbac must not import auth classes, so the token subject is read directly here. */
    private static UUID callerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
