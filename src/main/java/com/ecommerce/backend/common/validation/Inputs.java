package com.ecommerce.backend.common.validation;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Formats and normalization for free-text request fields, shared by every module. Request records
 * call these from their compact constructors, so validation sees the normalized value.
 */
public final class Inputs {

    /** Must match the {@code *_phone_e164} CHECKs (V10, V11). */
    public static final String E164 = "^\\+[1-9][0-9]{6,14}$";
    public static final String E164_MESSAGE = "must be in international format with country code, for example +493012345678";

    /**
     * Control characters and bidirectional overrides: invisible, and the overrides can make text
     * display as something else ("Trojan Source"). Other Unicode, accents and scripts are welcome.
     */
    public static final String NO_HIDDEN_CHARACTERS = "^[^\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]*$";
    public static final String HIDDEN_CHARACTERS_MESSAGE = "must not contain control or text-direction characters";

    /** Like {@link #NO_HIDDEN_CHARACTERS}, but line breaks and tabs are allowed: for multi-line text such as descriptions. */
    public static final String NO_HIDDEN_CHARACTERS_MULTILINE = "^(?:[\\n\\r\\t]|[^\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069])*$";

    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s().-]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private Inputs() {
    }

    /** Trimmed, and blank means not given. */
    public static String text(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /** "+49 (30) 123-456" becomes "+4930123456". */
    public static String phone(String value) {
        return value == null ? null : text(PHONE_SEPARATORS.matcher(value).replaceAll(""));
    }

    /** Upper case with single spaces: " sw1a  1aa " becomes "SW1A 1AA". */
    public static String code(String value) {
        String text = text(value);
        return text == null ? null : WHITESPACE.matcher(text).replaceAll(" ").toUpperCase(Locale.ROOT);
    }
}
