package com.ecommerce.backend.profile.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * How to address a person and how to reach them. Optional: an account without a profile is
 * normal, and a profile without names is too.
 *
 * <p>The key is the account's ID, referenced by ID only (the profile module does not depend on
 * auth's entities). No {@code BaseEntity}: there is no generated ID. Spring Data still knows a new
 * profile from its {@code null} version, so saving one is a plain INSERT.
 */
@Entity
@Table(name = "user_profiles")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserProfile {

    /** Everything the owner can set, already normalized and validated. {@code null} means not given. */
    public record Details(String givenName, String familyName, String phoneNumber, String preferredLocale,
                          String timeZone) {

        /** Names and phone number are personal data, and toString ends up in logs. */
        @Override
        public String toString() {
            return "Details[...]";
        }
    }

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "given_name", length = 100)
    private String givenName;

    @Column(name = "family_name", length = 100)
    private String familyName;

    @Column(name = "phone_number", length = 16)
    private String phoneNumber;

    @Column(name = "preferred_locale", length = 16)
    private String preferredLocale;

    @Column(name = "time_zone", length = 64)
    private String timeZone;

    /** Two devices saving at once: the second write fails instead of overwriting. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static UserProfile create(UUID userId, Details details) {
        UserProfile profile = new UserProfile();
        profile.userId = Objects.requireNonNull(userId, "userId");
        profile.change(details);
        return profile;
    }

    /** Replaces every field: a field that is not given is cleared (PUT semantics). */
    public void change(Details details) {
        Objects.requireNonNull(details, "details");
        this.givenName = details.givenName();
        this.familyName = details.familyName();
        this.phoneNumber = details.phoneNumber();
        this.preferredLocale = details.preferredLocale();
        this.timeZone = details.timeZone();
    }

    /** Personal data stays out of logs. */
    @Override
    public String toString() {
        return "UserProfile[userId=" + userId + ", version=" + version + "]";
    }
}
