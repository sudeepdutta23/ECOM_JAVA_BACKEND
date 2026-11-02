package com.ecommerce.backend.org.controller;

import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.org.OrganizationStatus;
import com.ecommerce.backend.org.dto.AdminOrganizationDetail;
import com.ecommerce.backend.org.dto.AdminOrganizationSummary;
import com.ecommerce.backend.org.dto.SuspendOrganizationRequest;
import com.ecommerce.backend.org.service.OrganizationAdminService;
import com.ecommerce.backend.rbac.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 * Platform administration of sellers. Platform permissions, not memberships, authorise these; the
 * path variable is {@code {organizationId}}, not {@code {orgId}}, because these are not
 * member-scoped endpoints (TenantIsolationIT covers the {@code {orgId}} ones).
 */
@RestController
@RequestMapping(path = "/api/v1/admin/organizations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Administration: organisations")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class OrganizationAdminController {

    private final OrganizationAdminService organizations;

    public OrganizationAdminController(OrganizationAdminService organizations) {
        this.organizations = organizations;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.ORG_READ + "')")
    @Operation(summary = "List organisations, newest first",
            description = "Requires org:read. status=PENDING_APPROVAL is the approval queue.")
    public ApiResponse<PageResponse<AdminOrganizationSummary>> list(
            @Parameter(description = "Exact status") @RequestParam(required = false) OrganizationStatus status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageResponse.MAX_PAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE) @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return ApiResponse.success("Organisations", organizations.list(status, page, size));
    }

    @GetMapping("/{organizationId}")
    @PreAuthorize("hasAuthority('" + Permissions.ORG_READ + "')")
    @Operation(summary = "One organisation with its status history and member counts", description = "Requires org:read.")
    public ApiResponse<AdminOrganizationDetail> get(@PathVariable UUID organizationId) {
        return ApiResponse.success("Organisation", organizations.get(organizationId));
    }

    @PostMapping("/{organizationId}/approve")
    @PreAuthorize("hasAuthority('" + Permissions.ORG_MANAGE + "')")
    @Operation(summary = "Approve a pending organisation",
            description = "Requires org:manage and no membership in it (403 ORG_CONFLICT_OF_INTEREST). 409 ORG_INVALID_STATUS.")
    public ApiResponse<AdminOrganizationDetail> approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId) {
        return ApiResponse.success("Organisation approved", organizations.approve(callerId(jwt), organizationId));
    }

    @PostMapping(path = "/{organizationId}/suspend", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.ORG_MANAGE + "')")
    @Operation(summary = "Suspend an active organisation",
            description = "Requires org:manage. The reason is internal, never shown to members. Members keep read access.")
    public ApiResponse<AdminOrganizationDetail> suspend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId,
                                                        @Valid @RequestBody SuspendOrganizationRequest request) {
        return ApiResponse.success("Organisation suspended",
                organizations.suspend(callerId(jwt), organizationId, request.reason()));
    }

    @PostMapping("/{organizationId}/reactivate")
    @PreAuthorize("hasAuthority('" + Permissions.ORG_MANAGE + "')")
    @Operation(summary = "Reactivate a suspended organisation", description = "Requires org:manage.")
    public ApiResponse<AdminOrganizationDetail> reactivate(@AuthenticationPrincipal Jwt jwt,
                                                           @PathVariable UUID organizationId) {
        return ApiResponse.success("Organisation reactivated", organizations.reactivate(callerId(jwt), organizationId));
    }

    @PostMapping("/{organizationId}/close")
    @PreAuthorize("hasAuthority('" + Permissions.ORG_MANAGE + "')")
    @Operation(summary = "Close an organisation for good, from any status",
            description = "Requires org:manage. Open invitations are revoked; data and the slug are kept.")
    public ApiResponse<AdminOrganizationDetail> close(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID organizationId) {
        return ApiResponse.success("Organisation closed", organizations.close(callerId(jwt), organizationId));
    }

    private static UUID callerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
