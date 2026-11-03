package com.ecommerce.backend.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST} and {@code PUT /api/v1/profile/addresses[/{addressId}]}: the whole address.
 * A field left out is cleared, the default flags included. Country-specific rules (postal code,
 * region) are checked by {@code AddressRules} in the service.
 */
@Schema(description = "An address. Optional fields you leave out are cleared, and so are the default flags")
public record AddressRequest(
        @Schema(example = "Home", description = "Your own name for it; never printed on a parcel")
        @Size(max = 50) @Pattern(regexp = Inputs.NO_HIDDEN_CHARACTERS, message = Inputs.HIDDEN_CHARACTERS_MESSAGE)
        String label,

        @Schema(example = "Ada Lovelace")
        @NotBlank @Size(max = 100) @Pattern(regexp = Inputs.NO_HIDDEN_CHARACTERS, message = Inputs.HIDDEN_CHARACTERS_MESSAGE)
        String recipientName,

        @Schema(example = "Analytical Engines Ltd")
        @Size(max = 100) @Pattern(regexp = Inputs.NO_HIDDEN_CHARACTERS, message = Inputs.HIDDEN_CHARACTERS_MESSAGE)
        String company,

        @Schema(example = "Friedrichstraße 123")
        @NotBlank @Size(max = 200) @Pattern(regexp = Inputs.NO_HIDDEN_CHARACTERS, message = Inputs.HIDDEN_CHARACTERS_MESSAGE)
        String line1,

        @Schema(example = "3rd floor")
        @Size(max = 200) @Pattern(regexp = Inputs.NO_HIDDEN_CHARACTERS, message = Inputs.HIDDEN_CHARACTERS_MESSAGE)
        String line2,

        @Schema(example = "Berlin")
        @NotBlank @Size(max = 100) @Pattern(regexp = Inputs.NO_HIDDEN_CHARACTERS, message = Inputs.HIDDEN_CHARACTERS_MESSAGE)
        String city,

        @Schema(example = "Berlin", description = "State, province or county; required in US, CA, AU and IN")
        @Size(max = 100) @Pattern(regexp = Inputs.NO_HIDDEN_CHARACTERS, message = Inputs.HIDDEN_CHARACTERS_MESSAGE)
        String region,

        @Schema(example = "10117", description = "Checked against the country's format where it is known")
        @Size(max = 20)
        String postalCode,

        @Schema(example = "DE", description = "ISO 3166-1 alpha-2")
        @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "must be a two-letter ISO country code such as DE")
        String countryCode,

        @Schema(example = "+493012345678", description = "For the courier; international format (E.164)")
        @Pattern(regexp = Inputs.E164, message = Inputs.E164_MESSAGE)
        String phoneNumber,

        @Schema(example = "true", description = "Make this the default shipping address. The first address always is")
        boolean defaultShipping,

        @Schema(example = "true", description = "Make this the default billing address. The first address always is")
        boolean defaultBilling,

        @Schema(example = "0", description = "Required when replacing: the version you read")
        Long version) {

    public AddressRequest {
        label = Inputs.text(label);
        recipientName = Inputs.text(recipientName);
        company = Inputs.text(company);
        line1 = Inputs.text(line1);
        line2 = Inputs.text(line2);
        city = Inputs.text(city);
        region = Inputs.text(region);
        postalCode = Inputs.code(postalCode);
        countryCode = Inputs.code(countryCode);
        phoneNumber = Inputs.phone(phoneNumber);
    }

    /** An address is personal data, and toString ends up in logs. */
    @Override
    public String toString() {
        return "AddressRequest[countryCode=" + countryCode + ", version=" + version + "]";
    }
}
