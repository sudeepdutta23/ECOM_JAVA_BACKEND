package com.ecommerce.backend.profile.dto;

import java.util.Locale;
import java.util.regex.Pattern;

/** Formats and normalization shared by the profile and address requests. */
final class Inputs {

    /** Must match the {@code *_phone_e164} CHECKs (V10, V11). */
    static final String E164 = "^\\+[1-9][0-9]{6,14}$";
    static final String E164_MESSAGE = "must be in international format with country code, for example +493012345678";

    /**
     * Control characters and bidirectional overrides: invisible, and the overrides can make text
     * display as something else ("Trojan Source"). Other Unicode, accents and scripts are welcome.
     */
    static final String NO_HIDDEN_CHARACTERS = "^[^\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]*$";
    static final String HIDDEN_CHARACTERS_MESSAGE = "must not contain control or text-direction characters";

    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s().-]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private Inputs() {
    }

    /** Trimmed, and blank means not given. */
    static String text(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** "+49 (30) 123-456" becomes "+4930123456". */
    static String phone(String value) {
        return value == null ? null : text(PHONE_SEPARATORS.matcher(value).replaceAll(""));
    }

    /** Upper case with single spaces: " sw1a  1aa " becomes "SW1A 1AA". */
    static String code(String value) {
        String text = text(value);
        return text == null ? null : WHITESPACE.matcher(text).replaceAll(" ").toUpperCase(Locale.ROOT);
    }
}
