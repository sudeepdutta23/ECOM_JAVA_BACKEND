package com.ecommerce.backend.profile.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserAddressTest {

    private static final UserAddress.Details HOME = new UserAddress.Details("Home", "Ada Lovelace", "Engines Ltd",
            "Friedrichstraße 123", "3rd floor", "Berlin", null, "10117", "DE", "+493012345678");

    @Test
    void changeReplacesEveryWrittenFieldButNotTheDefaults() {
        UserAddress address = UserAddress.create(UUID.randomUUID(), HOME);
        address.setDefaults(true, true);

        address.change(new UserAddress.Details(null, "Ada Lovelace", null, "Main St 1", null, "London", null,
                "SW1A 1AA", "GB", null));

        assertThat(address.getLabel()).isNull();
        assertThat(address.getCompany()).isNull();
        assertThat(address.getLine2()).isNull();
        assertThat(address.getCountryCode()).isEqualTo("GB");
        assertThat(address.isDefaultShipping()).isTrue();
        assertThat(address.isDefaultBilling()).isTrue();
    }

    @Test
    void recipientStreetCityAndCountryAreRequired() {
        UUID owner = UUID.randomUUID();
        assertThatThrownBy(() -> UserAddress.create(owner, new UserAddress.Details(null, null, null, "Street 1", null,
                "City", null, null, "DE", null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserAddress.create(owner, new UserAddress.Details(null, "Ada", null, " ", null,
                "City", null, null, "DE", null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserAddress.create(null, HOME)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void toStringKeepsTheAddressOutOfLogs() {
        UserAddress address = UserAddress.create(UUID.randomUUID(), HOME);

        assertThat(address.toString()).doesNotContain("Ada", "Friedrich", "10117", "+4930");
        assertThat(HOME.toString()).doesNotContain("Ada", "Friedrich", "10117", "+4930");
    }
}
