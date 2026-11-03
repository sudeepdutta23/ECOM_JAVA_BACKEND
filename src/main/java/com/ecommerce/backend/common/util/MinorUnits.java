package com.ecommerce.backend.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money is stored as a {@code long} of minor units (cents) and shown as a decimal with two places.
 * Never {@code double}: 0.1 + 0.2 is not 0.3 in binary floating point.
 *
 * <p>Two decimal places only. The platform currency is checked for that at startup
 * ({@code CatalogProperties}); currencies such as JPY (0) or KWD (3) need this class extended first.
 */
public final class MinorUnits {

    private static final int SCALE = 2;

    private MinorUnits() {
    }

    /** 19.99 becomes 1999. More than two decimal places is an error, never silently rounded. */
    public static long toMinor(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.UNNECESSARY).movePointRight(SCALE).longValueExact();
    }

    /** 1999 becomes 19.99, always with two decimal places. */
    public static BigDecimal toAmount(long minor) {
        return BigDecimal.valueOf(minor, SCALE);
    }
}
