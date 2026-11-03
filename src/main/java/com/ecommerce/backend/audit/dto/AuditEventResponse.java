package com.ecommerce.backend.audit.dto;

import com.ecommerce.backend.audit.entity.AuditEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "An audit log entry")
public record AuditEventResponse(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        Instant occurredAt,
        @Schema(description = "The account that acted; absent for the system") UUID actorId,
        @Schema(example = "user.suspend") String action,
        @Schema(example = "user") String targetType,
        UUID targetId,
        UUID organizationId,
        @Schema(example = "role=01a1868e-9cf4-79d1-b09b-dedd85868415") String detail,
        @Schema(description = "Matches the X-Request-Id and the application log") String requestId,
        String clientIp) {

    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(event.getId(), event.getOccurredAt(), event.getActorId(), event.getAction(),
                event.getTargetType(), event.getTargetId(), event.getOrganizationId(), event.getDetail(),
                event.getRequestId(), event.getClientIp());
    }
}
