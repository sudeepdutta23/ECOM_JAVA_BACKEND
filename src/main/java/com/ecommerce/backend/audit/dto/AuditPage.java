package com.ecommerce.backend.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * One page of the audit log, newest first, with a keyset cursor instead of page numbers: the log
 * grows while someone reads it, and "page 3" would shift under them (ADR-011).
 */
@Schema(description = "Audit entries, newest first")
public record AuditPage(
        List<AuditEventResponse> items,
        @Schema(description = "Pass as ?before= for the next (older) page; absent on the last page") UUID nextBefore) {
}
