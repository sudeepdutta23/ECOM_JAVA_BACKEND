package com.ecommerce.backend.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Payload of {@code GET /api/v1/system/ping}. */
@Schema(description = "Liveness information for API clients")
public record PingResponse(
        @Schema(example = "UP") String status,
        @Schema(example = "ecommerce-backend") String service,
        @Schema(example = "2026-10-26T10:15:30.123Z") Instant serverTime) {
}
