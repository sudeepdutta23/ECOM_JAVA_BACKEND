package com.ecommerce.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ecommerce.backend.auth.entity.AccountToken;
import com.ecommerce.backend.auth.entity.TokenPurpose;
import com.ecommerce.backend.auth.entity.User;
import com.ecommerce.backend.auth.repository.AccountTokenRepository;
import com.ecommerce.backend.auth.security.OpaqueTokens;
import com.ecommerce.backend.common.exception.BusinessException;
import com.ecommerce.backend.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class AccountTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration TTL = Duration.ofHours(24);

    private final AccountTokenRepository repository = mock(AccountTokenRepository.class);
    private final AccountTokenService service = new AccountTokenService(
            repository, new OpaqueTokens(), Clock.fixed(NOW, ZoneOffset.UTC));
    private final User user = User.register("alice@example.com", "{argon2}h", NOW);

    @Test
    void issueInvalidatesOlderTokensThenStoresOnlyTheHash() {
        when(repository.save(any(AccountToken.class))).thenAnswer(i -> i.getArgument(0));

        String raw = service.issue(user, TokenPurpose.EMAIL_VERIFICATION, TTL);

        InOrder order = inOrder(repository);
        order.verify(repository).consumeAllActive(user.getId(), TokenPurpose.EMAIL_VERIFICATION, NOW);
        ArgumentCaptor<AccountToken> saved = ArgumentCaptor.forClass(AccountToken.class);
        order.verify(repository).save(saved.capture());

        assertThat(raw).matches(OpaqueTokens.FORMAT_REGEX);
        assertThat(saved.getValue().getTokenHash()).isEqualTo(OpaqueTokens.hash(raw)).isNotEqualTo(raw);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void consumeMarksTokenUsedAndReturnsUser() {
        AccountToken token = stored(TokenPurpose.EMAIL_VERIFICATION, NOW.minusSeconds(60));

        User result = service.consume("raw-token", TokenPurpose.EMAIL_VERIFICATION);

        assertThat(result).isSameAs(user);
        assertThat(token.getConsumedAt()).isEqualTo(NOW);
    }

    @Test
    void unknownTokenIsRejected() {
        when(repository.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());

        assertInvalid(TokenPurpose.EMAIL_VERIFICATION);
    }

    @Test
    void expiredTokenIsRejected() {
        stored(TokenPurpose.EMAIL_VERIFICATION, NOW.minus(TTL).minusSeconds(1));

        assertInvalid(TokenPurpose.EMAIL_VERIFICATION);
    }

    @Test
    void tokenForAnotherPurposeIsRejected() {
        stored(TokenPurpose.PASSWORD_RESET, NOW.minusSeconds(60));

        assertInvalid(TokenPurpose.EMAIL_VERIFICATION);
    }

    @Test
    void alreadyConsumedTokenIsRejected() {
        AccountToken token = stored(TokenPurpose.EMAIL_VERIFICATION, NOW.minusSeconds(60));
        token.consume(NOW.minusSeconds(30));

        assertInvalid(TokenPurpose.EMAIL_VERIFICATION);
    }

    private AccountToken stored(TokenPurpose purpose, Instant issuedAt) {
        AccountToken token = AccountToken.issue(user, purpose, OpaqueTokens.hash("raw-token"), issuedAt, TTL);
        when(repository.findByTokenHashForUpdate(OpaqueTokens.hash("raw-token"))).thenReturn(Optional.of(token));
        return token;
    }

    private void assertInvalid(TokenPurpose purpose) {
        assertThatThrownBy(() -> service.consume("raw-token", purpose))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_ACCOUNT_TOKEN));
    }
}
