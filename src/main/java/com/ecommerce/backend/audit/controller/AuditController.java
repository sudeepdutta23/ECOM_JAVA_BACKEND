package com.ecommerce.backend.audit.controller;

import com.ecommerce.backend.audit.dto.AuditPage;
import com.ecommerce.backend.audit.service.AuditQueryService;
import com.ecommerce.backend.common.config.OpenApiConfig;
import com.ecommerce.backend.common.response.ApiResponse;
import com.ecommerce.backend.common.response.PageResponse;
import com.ecommerce.backend.rbac.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit log, read only: there is no endpoint, and no SQL path, that changes it. */
@RestController
@RequestMapping(path = "/api/v1/admin/audit", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Administration: audit")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AuditController {

    private final AuditQueryService audit;

    public AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.AUDIT_READ + "')")
    @Operation(summary = "Audit entries, newest first, with optional filters",
            description = "Needs audit:read. Keyset paging: pass the response's nextBefore as ?before= for older entries.")
    public ApiResponse<AuditPage> search(
            @Parameter(description = "Who acted") @RequestParam(required = false) UUID actorId,
            @Parameter(description = "What was acted on") @RequestParam(required = false) UUID targetId,
            @RequestParam(required = false) UUID organizationId,
            @Parameter(description = "For example user.suspend")
            @RequestParam(required = false) @Pattern(regexp = "^[a-z]+(\\.[a-z-]+)+$") String action,
            @Parameter(description = "Cursor: entries older than this ID") @RequestParam(required = false) UUID before,
            @RequestParam(defaultValue = PageResponse.DEFAULT_SIZE) @Min(1) @Max(PageResponse.MAX_SIZE) int size) {
        return ApiResponse.success("Audit entries", audit.search(actorId, targetId, organizationId, action, before, size));
    }
}
