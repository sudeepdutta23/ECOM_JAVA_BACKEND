package com.ecommerce.backend.profile.entity;

import com.ecommerce.backend.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
 * A saved address in a person's address book. Its owner is referenced by ID only.
 *
 * <p>Default flags are set by the service, which first clears the previous default under the
 * account's address-book lock; {@code uk_user_addresses_default_*} (V11) backs that up.
 */
@Entity
@Table(name = "user_addresses")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAddress extends BaseEntity {

    /** Everything the owner can write, already normalized and validated. {@code null} means not given. */
    public record Details(String label, String recipientName, String company, String line1, String line2,
                          String city, String region, String postalCode, String countryCode, String phoneNumber) {

        /** An address is personal data, and toString ends up in logs. */
        @Override
        public String toString() {
            return "Details[countryCode=" + countryCode + "]";
        }
    }

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "label", length = 50)
    private String label;

    @Column(name = "recipient_name", nullable = false, length = 100)
    private String recipientName;

    @Column(name = "company", length = 100)
    private String company;

    @Column(name = "line1", nullable = false, length = 200)
    private String line1;

    @Column(name = "line2", length = 200)
    private String line2;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "region", length = 100)
    private String region;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(name = "phone_number", length = 16)
    private String phoneNumber;

    @Column(name = "default_shipping", nullable = false)
    private boolean defaultShipping;

    @Column(name = "default_billing", nullable = false)
    private boolean defaultBilling;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static UserAddress create(UUID userId, Details details) {
        UserAddress address = new UserAddress();
        address.userId = Objects.requireNonNull(userId, "userId");
        address.change(details);
        return address;
    }

    /** Replaces every written field (PUT semantics). The default flags are set separately. */
    public void change(Details details) {
        Objects.requireNonNull(details, "details");
        this.label = details.label();
        this.recipientName = requireText(details.recipientName(), "recipientName");
        this.company = details.company();
        this.line1 = requireText(details.line1(), "line1");
        this.line2 = details.line2();
        this.city = requireText(details.city(), "city");
        this.region = details.region();
        this.postalCode = details.postalCode();
        this.countryCode = requireText(details.countryCode(), "countryCode");
        this.phoneNumber = details.phoneNumber();
    }

    public void setDefaults(boolean shipping, boolean billing) {
        this.defaultShipping = shipping;
        this.defaultBilling = billing;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An address needs " + field);
        }
        return value;
    }

    /** Personal data stays out of logs. */
    @Override
    public String toString() {
        return "UserAddress[id=" + getId() + ", userId=" + userId + ", countryCode=" + countryCode + "]";
    }
}
