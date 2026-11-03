package com.ecommerce.backend.profile.service;

import com.ecommerce.backend.common.response.FieldErrorDetail;
import com.ecommerce.backend.profile.entity.UserAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Country-specific address rules, beyond what annotations can say.
 *
 * <ul>
 *   <li>The country must be an ISO 3166-1 alpha-2 code ({@code GB}, not {@code UK}).</li>
 *   <li>Where the postal-code format is listed below, the code is required and must match it.
 *       Elsewhere it is optional and only its general shape is checked: many countries have no
 *       postal codes at all (Hong Kong, the United Arab Emirates).</li>
 *   <li>Some countries need a state or province for delivery.</li>
 * </ul>
 *
 * The lists cover the main markets and grow with the countries the shop ships to. A full address
 * validation service (Google, Loqate) is the option once checkout needs verified deliverability.
 */
final class AddressRules {

    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());

    /** Patterns apply to the normalized code: upper case, single spaces. */
    private static final Map<String, Pattern> POSTAL_CODES = Map.ofEntries(
            Map.entry("AT", Pattern.compile("\\d{4}")),
            Map.entry("AU", Pattern.compile("\\d{4}")),
            Map.entry("BE", Pattern.compile("\\d{4}")),
            Map.entry("CA", Pattern.compile("[A-Z]\\d[A-Z] ?\\d[A-Z]\\d")),
            Map.entry("CH", Pattern.compile("\\d{4}")),
            Map.entry("DE", Pattern.compile("\\d{5}")),
            Map.entry("DK", Pattern.compile("\\d{4}")),
            Map.entry("ES", Pattern.compile("\\d{5}")),
            Map.entry("FR", Pattern.compile("\\d{5}")),
            Map.entry("GB", Pattern.compile("[A-Z]{1,2}\\d[A-Z\\d]? ?\\d[A-Z]{2}")),
            Map.entry("IN", Pattern.compile("\\d{6}")),
            Map.entry("IT", Pattern.compile("\\d{5}")),
            Map.entry("NL", Pattern.compile("\\d{4} ?[A-Z]{2}")),
            Map.entry("PL", Pattern.compile("\\d{2}-\\d{3}")),
            Map.entry("SE", Pattern.compile("\\d{3} ?\\d{2}")),
            Map.entry("US", Pattern.compile("\\d{5}(-\\d{4})?")));

    /** Must match {@code ck_user_addresses_postal_code_format} (V11). */
    private static final Pattern POSTAL_CODE_SHAPE = Pattern.compile("[A-Z0-9]([A-Z0-9 -]{0,18}[A-Z0-9])?");

    private static final Set<String> REGION_REQUIRED = Set.of("AU", "CA", "IN", "US");

    private AddressRules() {
    }

    /** Every violation at once, so the form can mark all fields in one round trip. */
    static List<FieldErrorDetail> check(UserAddress.Details address) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        String country = address.countryCode();
        if (!COUNTRIES.contains(country)) {
            errors.add(new FieldErrorDetail("countryCode", "is not an ISO 3166-1 country code"));
            return errors; // the other rules depend on the country
        }
        String postalCode = address.postalCode();
        Pattern format = POSTAL_CODES.get(country);
        if (format != null && postalCode == null) {
            errors.add(new FieldErrorDetail("postalCode", "is required in " + country));
        } else if (format != null && !format.matcher(postalCode).matches()) {
            errors.add(new FieldErrorDetail("postalCode", "is not a valid postal code in " + country));
        } else if (postalCode != null && !POSTAL_CODE_SHAPE.matcher(postalCode).matches()) {
            errors.add(new FieldErrorDetail("postalCode", "may contain only letters, digits, spaces and hyphens"));
        }
        if (REGION_REQUIRED.contains(country) && address.region() == null) {
            errors.add(new FieldErrorDetail("region", "is required in " + country));
        }
        return errors;
    }
}
