package com.ecommerce.backend.profile.dto;

import com.ecommerce.backend.profile.entity.UserAddress;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** One address of the caller's address book. Optional fields that were not given are absent. */
@Schema(description = "An address in your address book")
public record AddressResponse(
        @Schema(example = "01a1868e-9cf4-79d1-b09b-dedd85868415") UUID id,
        @Schema(example = "Home") String label,
        @Schema(example = "Ada Lovelace") String recipientName,
        String company,
        @Schema(example = "Friedrichstraße 123") String line1,
        String line2,
        @Schema(example = "Berlin") String city,
        String region,
        @Schema(example = "10117") String postalCode,
        @Schema(example = "DE") String countryCode,
        String phoneNumber,
        boolean defaultShipping,
        boolean defaultBilling,
        @Schema(example = "0", description = "Send it back when replacing") long version,
        Instant createdAt,
        Instant updatedAt) {

    public static AddressResponse from(UserAddress address) {
        return new AddressResponse(address.getId(), address.getLabel(), address.getRecipientName(), address.getCompany(),
                address.getLine1(), address.getLine2(), address.getCity(), address.getRegion(), address.getPostalCode(),
                address.getCountryCode(), address.getPhoneNumber(), address.isDefaultShipping(),
                address.isDefaultBilling(), address.getVersion(), address.getCreatedAt(), address.getUpdatedAt());
    }

    /** Personal data stays out of logs. */
    @Override
    public String toString() {
        return "AddressResponse[id=" + id + ", version=" + version + "]";
    }
}
