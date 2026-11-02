package com.ecommerce.backend.auth.dto;

import com.ecommerce.backend.auth.repository.RefreshTokenRepository.SessionRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One signed-in device of the current user")
public record SessionResponse(
        @Schema(example = "01a18c2e-77aa-7b10-9a51-1f2e3d4c5b6a") UUID sessionId,
        @Schema(description = "When the user signed in on this device") Instant startedAt,
        @Schema(description = "Last token refresh, roughly the last use") Instant lastActiveAt,
        @Schema(description = "When the session ends unless it is used again") Instant expiresAt,
        @Schema(example = "203.0.113.7", description = "IP of the last refresh") String ip,
        @Schema(example = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ...") String userAgent,
        @Schema(example = "true", description = "The session making this request") boolean current) {

    public static SessionResponse from(SessionRow row, UUID currentSessionId) {
        return new SessionResponse(row.getSessionId(), row.getStartedAt(), row.getLastActiveAt(), row.getExpiresAt(),
                row.getIp(), row.getUserAgent(), row.getSessionId().equals(currentSessionId));
    }
}
