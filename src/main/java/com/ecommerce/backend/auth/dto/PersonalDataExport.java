package com.ecommerce.backend.auth.dto;

import com.ecommerce.backend.auth.entity.UserStatus;
import com.ecommerce.backend.org.MembershipSummary;
import com.ecommerce.backend.profile.PersonalProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "Download my data": everything the platform holds about the caller, from every module, in one
 * machine-readable document (GDPR articles 15 and 20).
 *
 * <p>Left out on purpose: the password hash and token hashes (security material, never exposed),
 * and internal moderation notes such as a suspension reason, which are disclosed on request through
 * support, after review. Data of later phases (orders, files) joins this document with its module.
 */
@Schema(description = "Everything the platform holds about you")
public record PersonalDataExport(
        @Schema(description = "When this export was made") Instant exportedAt,
        Account account,
        @Schema(example = "[\"CUSTOMER\"]") List<String> roles,
        @Schema(description = "Signed-in devices with their last IP address and browser") List<SessionResponse> sessions,
        @Schema(description = "Seller organisations you belong to") List<MembershipSummary> organizations,
        PersonalProfile profile) {

    /** The account itself, without any credential. */
    public record Account(UUID id, String email, UserStatus status, Instant emailVerifiedAt, Instant lastLoginAt,
                          Instant createdAt) {

        @Override
        public String toString() {
            return "Account[id=" + id + "]";
        }
    }

    /** Personal data stays out of logs. */
    @Override
    public String toString() {
        return "PersonalDataExport[account=" + account + "]";
    }
}
