package com.ecommerce.backend.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.common.response.FieldErrorDetail;
import com.ecommerce.backend.profile.entity.UserAddress;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AddressRulesTest {

    private static List<String> fieldsWithErrors(String country, String postalCode, String region) {
        UserAddress.Details address = new UserAddress.Details(null, "Ada", null, "Street 1", null, "City", region,
                postalCode, country, null);
        return AddressRules.check(address).stream().map(FieldErrorDetail::field).toList();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(nullValues = "-", value = {
            "DE, 10117,    -",
            "AT, 1010,     -",
            "GB, SW1A 1AA, -",
            "GB, M1 1AE,   -",
            "GB, EC1A1BB,  -",
            "NL, 1012 AB,  -",
            "NL, 1012AB,   -",
            "US, 94105,    CA",
            "US, 94105-1234, CA",
            "CA, K1A 0B1,  ON",
            "IN, 110001,   Delhi",
            "AU, 2000,     NSW",
            "PL, 00-950,   -",
            "SE, 114 55,   -",
            "HK, -,        -",     // no postal codes in Hong Kong
            "AE, -,        -",
            "BR, 01310-100, -"     // format not listed: only the general shape is checked
    })
    void validAddressesPass(String country, String postalCode, String region) {
        assertThat(fieldsWithErrors(country, postalCode, region)).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1} -> {3}")
    @CsvSource(nullValues = "-", value = {
            "UK, SW1A 1AA, -,  countryCode",   // the ISO code is GB
            "XX, 12345,    -,  countryCode",
            "DE, 1011,     -,  postalCode",
            "DE, -,        -,  postalCode",    // required where the format is known
            "GB, 12345,    -,  postalCode",
            "US, 9410,     CA, postalCode",
            "US, 94105,    -,  region",
            "CA, 12345,    -,  postalCode",
            "BR, 01310_100, -, postalCode"     // general shape: letters, digits, spaces, hyphens
    })
    void invalidAddressesNameTheField(String country, String postalCode, String region, String field) {
        assertThat(fieldsWithErrors(country, postalCode, region)).contains(field);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"US", "CA"})
    void everyViolationIsReportedAtOnce(String country) {
        assertThat(fieldsWithErrors(country, null, null)).containsExactlyInAnyOrder("postalCode", "region");
    }
}
