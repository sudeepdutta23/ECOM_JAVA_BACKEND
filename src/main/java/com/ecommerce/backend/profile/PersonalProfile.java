package com.ecommerce.backend.profile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything the profile module holds about one person, as one read-only value: the profile
 * module's public view for other modules ("download my data" in auth) and for support staff.
 * Fields that were never given are {@code null}; the API omits them.
 */
public record PersonalProfile(
        String givenName,
        String familyName,
        String phoneNumber,
        String preferredLocale,
        String timeZone,
        Instant profileUpdatedAt,
        List<Address> addresses) {

    public PersonalProfile {
        addresses = List.copyOf(addresses);
    }

    /** One saved address. */
    public record Address(UUID id, String label, String recipientName, String company, String line1, String line2,
                          String city, String region, String postalCode, String countryCode, String phoneNumber,
                          boolean defaultShipping, boolean defaultBilling, Instant createdAt, Instant updatedAt) {

        /** Personal data stays out of logs. */
        @Override
        public String toString() {
            return "Address[id=" + id + "]";
        }
    }

    /** Personal data stays out of logs. */
    @Override
    public String toString() {
        return "PersonalProfile[addresses=" + addresses.size() + "]";
    }
}
