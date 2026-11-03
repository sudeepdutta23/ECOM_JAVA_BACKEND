package com.ecommerce.backend.profile.dto;

import com.ecommerce.backend.profile.entity.UserProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * The caller's own profile. Fields that were never given are absent (the API omits nulls); so is
 * {@code version} until the first save.
 */
@Schema(description = "Your profile")
public record ProfileResponse(
        @Schema(example = "Ada") String givenName,
        @Schema(example = "Lovelace") String familyName,
        @Schema(example = "+493012345678") String phoneNumber,
        @Schema(example = "en-GB") String preferredLocale,
        @Schema(example = "Europe/Berlin") String timeZone,
        @Schema(example = "0", description = "Send it back when saving; absent until the first save") Long version,
        Instant updatedAt) {

    /** An account that never saved a profile. */
    public static final ProfileResponse EMPTY = new ProfileResponse(null, null, null, null, null, null, null);

    public static ProfileResponse from(UserProfile profile) {
        return new ProfileResponse(profile.getGivenName(), profile.getFamilyName(), profile.getPhoneNumber(),
                profile.getPreferredLocale(), profile.getTimeZone(), profile.getVersion(), profile.getUpdatedAt());
    }

    /** Personal data stays out of logs. */
    @Override
    public String toString() {
        return "ProfileResponse[version=" + version + "]";
    }
}
