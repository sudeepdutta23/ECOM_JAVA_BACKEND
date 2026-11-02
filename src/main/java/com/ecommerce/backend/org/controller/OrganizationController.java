package com.ecommerce.backend.org.controller;

import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.org.dto.CreateOrganizationRequest;
import com.ecommerce.backend.org.dto.MyOrganizationResponse;
import com.ecommerce.backend.org.dto.OrganizationResponse;
import com.ecommerce.backend.org.dto.UpdateOrganizationRequest;
import com.ecommerce.backend.org.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sellers as their members see them. Any signed-in account may create one; everything else
 * depends on the caller's role in that organisation, checked per request (ADR-012), so there is no
 * {@code @PreAuthorize} here. Non-members get 404 for every {@code {orgId}}.
 */
@RestController
@RequestMapping(path = "/api/v1/organizations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Organisations")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class OrganizationController {

    private final OrganizationService organizations;

    public OrganizationController(OrganizationService organizations) {
        this.organizations = organizations;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create a seller organisation; you become its owner",
            description = "It starts PENDING_APPROVAL and cannot sell until the platform approves it.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Created; Location header"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                            description = "VALIDATION_FAILED, including a reserved slug"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                            description = "ORG_SLUG_TAKEN or ORG_LIMIT_REACHED")})
    public ResponseEntity<ApiResponse<OrganizationResponse>> create(@AuthenticationPrincipal Jwt jwt,
                                                                    @Valid @RequestBody CreateOrganizationRequest request) {
        OrganizationResponse created = organizations.create(callerId(jwt), request.slug(), request.name());
        return ResponseEntity.created(URI.create("/api/v1/organizations/" + created.id()))
                .body(ApiResponse.success("Organisation created", created));
    }

    @GetMapping
    @Operation(summary = "My organisations, newest first, with my role in each")
    public ApiResponse<PageResponse<MyOrganizationResponse>> mine(
            @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Zero-based page index")
            @RequestParam(defaultValue = "0") @Min(0) @Max(PageResponse.MAX_PAGE) int page,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE) @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return ApiResponse.success("Organisations", organizations.mine(callerId(jwt), page, size));
    }

    @GetMapping("/{orgId}")
    @Operation(summary = "One of my organisations, with what my role allows",
            description = "404 RESOURCE_NOT_FOUND if it does not exist or you are not a member.")
    public ApiResponse<OrganizationResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId) {
        return ApiResponse.success("Organisation", organizations.get(callerId(jwt), orgId));
    }

    @PutMapping(path = "/{orgId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Rename an organisation (the slug never changes)",
            description = "Needs ORGANIZATION_UPDATE (owner or manager). Send the version you read.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                            description = "FORBIDDEN: your role does not allow it"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                            description = "CONCURRENT_MODIFICATION or ORG_INVALID_STATUS (suspended or closed)")})
    public ApiResponse<OrganizationResponse> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId,
                                                    @Valid @RequestBody UpdateOrganizationRequest request) {
        return ApiResponse.success("Organisation updated",
                organizations.update(callerId(jwt), orgId, request.name(), request.version()));
    }

    @PostMapping("/{orgId}/close")
    @Operation(summary = "Close the organisation for good",
            description = "Owners only (ORGANIZATION_CLOSE). Data and slug are kept; open invitations are revoked. "
                    + "409 ORG_INVALID_STATUS if suspended (only the platform can close it then) or already closed.")
    public ApiResponse<OrganizationResponse> close(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orgId) {
        return ApiResponse.success("Organisation closed", organizations.close(callerId(jwt), orgId));
    }

    private static UUID callerId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
