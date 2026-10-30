package com.ecommerce.backend.auth.entity;

import com.ecommerce.backend.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One-time token for email verification or password reset. Only the hash is stored. */
@Entity
@Table(name = "account_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, updatable = false, length = 32)
    private TokenPurpose purpose;

    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    /**
     * Set from the same clock as {@code expiresAt}, not by auditing, so the database
     * check {@code expires_at > created_at} can never fail on clock differences.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static AccountToken issue(User user, TokenPurpose purpose, String tokenHash, Instant now, Duration ttl) {
        AccountToken token = new AccountToken();
        token.user = Objects.requireNonNull(user, "user");
        token.purpose = Objects.requireNonNull(purpose, "purpose");
        token.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        token.createdAt = now;
        token.expiresAt = now.plus(ttl);
        return token;
    }

    public boolean isUsableAt(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }

    public void consume(Instant now) {
        if (consumedAt != null) {
            throw new IllegalStateException("Account token already consumed");
        }
        this.consumedAt = now;
    }

    @Override
    public String toString() {
        return "AccountToken[id=" + getId() + ", purpose=" + purpose + "]";
    }
}
