package com.ecommerce.backend.rbac.controller;

import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.rbac.Permissions;
import com.ecommerce.backend.rbac.dto.PermissionResponse;
import com.ecommerce.backend.rbac.service.PermissionCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The permission catalogue, for admin screens that build roles. */
@RestController
@RequestMapping(path = "/api/v1/admin/permissions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Administration: roles and permissions")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class PermissionController {

    private final PermissionCatalogService permissions;

    public PermissionController(PermissionCatalogService permissions) {
        this.permissions = permissions;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_READ + "')")
    @Operation(summary = "List all permissions", description = "Requires role:read.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OK"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN")})
    public ApiResponse<List<PermissionResponse>> list() {
        return ApiResponse.success("Permissions",
                permissions.list().stream().map(PermissionResponse::from).toList());
    }
}
