package com.ecommerce.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.auth.dto.SessionResponse;
import com.ecommerce.backend.auth.entity.RevokeReason;
import com.ecommerce.backend.auth.repository.RefreshTokenRepository;
import com.ecommerce.backend.auth.repository.RefreshTokenRepository.SessionRow;
import com.ecommerce.backend.common.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    private final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
    private final SessionService service = new SessionService(tokens, Clock.fixed(NOW, ZoneOffset.UTC));

    private record Row(UUID getSessionId, Instant getStartedAt, Instant getLastActiveAt, Instant getExpiresAt,
                       String getIp, String getUserAgent) implements SessionRow {
    }

    private static SessionRow row(UUID sessionId) {
        return new Row(sessionId, NOW.minusSeconds(600), NOW.minusSeconds(60), NOW.plusSeconds(3600), "203.0.113.7", "JUnit");
    }

    @Test
    void marksOnlyTheCallersSessionAsCurrent() {
        UUID mine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(tokens.findActiveSessions(USER, NOW)).thenReturn(List.of(row(mine), row(other)));

        List<SessionResponse> sessions = service.list(USER, mine);

        assertThat(sessions).extracting(SessionResponse::current).containsExactly(true, false);
    }

    @Test
    void aTokenWithoutSidMarksNothingAsCurrent() {
        when(tokens.findActiveSessions(USER, NOW)).thenReturn(List.of(row(UUID.randomUUID())));

        assertThat(service.list(USER, null)).extracting(SessionResponse::current).containsExactly(false);
    }

    @Test
    void endingRevokesTheWholeFamily() {
        UUID session = UUID.randomUUID();
        when(tokens.existsByFamilyIdAndUser_Id(session, USER)).thenReturn(true);

        service.end(USER, session);

        verify(tokens).revokeAllActiveByFamilyId(session, RevokeReason.LOGOUT, NOW);
    }

    @Test
    void someoneElsesOrAnUnknownSessionIs404AndNothingIsRevoked() {
        UUID notMine = UUID.randomUUID();
        when(tokens.existsByFamilyIdAndUser_Id(notMine, USER)).thenReturn(false);

        assertThatThrownBy(() -> service.end(USER, notMine)).isInstanceOf(ResourceNotFoundException.class);
        verify(tokens, never()).revokeAllActiveByFamilyId(any(), any(), any());
    }
}
