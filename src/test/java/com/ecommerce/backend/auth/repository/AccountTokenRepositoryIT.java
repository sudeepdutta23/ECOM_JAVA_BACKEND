package com.ecommerce.backend.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.auth.entity.AccountToken;
import com.ecommerce.backend.auth.entity.TokenPurpose;
import com.ecommerce.backend.auth.entity.User;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AccountTokenRepositoryIT extends AbstractAuthRepositoryIT {

    @Autowired
    private AccountTokenRepository tokens;

    private AccountToken savedToken(User user, TokenPurpose purpose, String hash) {
        return tokens.save(AccountToken.issue(user, purpose, hash, NOW, Duration.ofHours(1)));
    }

    @Test
    void consumeAllActiveInvalidatesOnlyTheGivenPurpose() {
        User user = savedUser();
        AccountToken oldLink = savedToken(user, TokenPurpose.EMAIL_VERIFICATION, randomHash());
        AccountToken olderLink = savedToken(user, TokenPurpose.EMAIL_VERIFICATION, randomHash());
        AccountToken reset = savedToken(user, TokenPurpose.PASSWORD_RESET, randomHash());

        Integer consumed = tx.execute(s ->
                tokens.consumeAllActive(user.getId(), TokenPurpose.EMAIL_VERIFICATION, NOW));

        assertThat(consumed).isEqualTo(2);
        assertThat(tokens.findById(oldLink.getId()).orElseThrow().getConsumedAt()).isEqualTo(NOW);
        assertThat(tokens.findById(olderLink.getId()).orElseThrow().getConsumedAt()).isEqualTo(NOW);
        assertThat(tokens.findById(reset.getId()).orElseThrow().getConsumedAt()).isNull();
    }

    @Test
    void consumingThroughLockedLookupIsPersisted() {
        User user = savedUser();
        String hash = randomHash();
        AccountToken token = savedToken(user, TokenPurpose.PASSWORD_RESET, hash);

        tx.executeWithoutResult(s -> tokens.findByTokenHashForUpdate(hash).orElseThrow().consume(NOW));

        AccountToken reloaded = tokens.findById(token.getId()).orElseThrow();
        assertThat(reloaded.isUsableAt(NOW)).isFalse();
        assertThat(reloaded.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void unknownHashIsNotFound() {
        Boolean present = tx.execute(s -> tokens.findByTokenHashForUpdate(randomHash()).isPresent());

        assertThat(present).isFalse();
    }
}
