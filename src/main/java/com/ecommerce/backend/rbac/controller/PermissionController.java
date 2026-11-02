package com.ecommerce.backend.rbac.controller;

import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.rbac.Permissions;
import com.ecommerce.backend.rbac.dto.PermissionResponse;
import com.ecommerce.backend.rbac.repository.PermissionDefinitionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The permission catalogue, for admin screens that build roles.
 *
 * <p>Read-only and tiny, so the controller reads the repository directly; a service layer would
 * add nothing here. Logic-bearing operations always go through a service.
 */
@RestController
@RequestMapping(path = "/api/v1/admin/permissions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Administration: roles and permissions")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class PermissionController {

    private final PermissionDefinitionRepository permissions;

    public PermissionController(PermissionDefinitionRepository permissions) {
        this.permissions = permissions;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_READ + "')")
    @Operation(summary = "List all permissions", description = "Requires role:read.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN")})
    public ApiResponse<List<PermissionResponse>> list() {
        return ApiResponse.success("Permissions",
                permissions.findAllByOrderByCodeAsc().stream().map(PermissionResponse::from).toList());
    }
}
