package com.ecommerce.backend.profile.dto;

import com.ecommerce.backend.common.validation.Inputs;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * Body of {@code PUT /api/v1/profile}: the whole profile, every field optional. A field that is
 * left out or blank is cleared, so the client always sends what the person sees in the form.
 * Unknown fields (a {@code userId}, say) are rejected by the global Jackson setting.
 */
@Schema(description = "Your profile. Every field is optional; one you leave out is cleared")
public record UpdateProfileRequest(
        @Schema(example = "Ada")
        @Size(max = 100) @Pattern(regexp = Inputs.NO_HIDDEN_CHARACTERS, message = Inputs.HIDDEN_CHARACTERS_MESSAGE)
        String givenName,

        @Schema(example = "Lovelace")
        @Size(max = 100) @Pattern(regexp = Inputs.NO_HIDDEN_CHARACTERS, message = Inputs.HIDDEN_CHARACTERS_MESSAGE)
        String familyName,

        @Schema(example = "+493012345678",
                description = "International format (E.164) with country code. Spaces, hyphens, dots and brackets are removed")
        @Pattern(regexp = Inputs.E164, message = Inputs.E164_MESSAGE)
        String phoneNumber,

        @Schema(example = "en-GB", description = "Language, optionally with region (BCP 47)")
        @Pattern(regexp = LOCALE, message = "must be a language tag such as de or en-GB")
        String preferredLocale,

        @Schema(example = "Europe/Berlin", description = "IANA time zone ID")
        @Size(max = 64)
        String timeZone,

        @Schema(example = "0", description = "Version from your last read. Leave it out for your first save")
        Long version) {

    /** Must match {@code ck_user_profiles_locale_format} (V10). */
    static final String LOCALE = "^[a-z]{2,3}(-[A-Z]{2})?$";

    public UpdateProfileRequest {
        givenName = Inputs.text(givenName);
        familyName = Inputs.text(familyName);
        phoneNumber = Inputs.phone(phoneNumber);
        preferredLocale = locale(Inputs.text(preferredLocale));
        timeZone = Inputs.text(timeZone);
    }

    /** "EN-gb" becomes "en-GB"; anything that is not language[-region] is left for validation to reject. */
    private static String locale(String tag) {
        if (tag == null || !tag.matches("(?i)[a-z]{2,3}(-[a-z]{2})?")) {
            return tag;
        }
        int dash = tag.indexOf('-');
        return dash < 0 ? tag.toLowerCase(Locale.ROOT)
                : tag.substring(0, dash).toLowerCase(Locale.ROOT) + "-" + tag.substring(dash + 1).toUpperCase(Locale.ROOT);
    }

    /** Names and phone numbers are personal data, and toString ends up in logs. */
    @Override
    public String toString() {
        return "UpdateProfileRequest[version=" + version + "]";
    }
}
