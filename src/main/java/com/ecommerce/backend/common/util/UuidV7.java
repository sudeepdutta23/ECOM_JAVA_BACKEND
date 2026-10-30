package com.ecommerce.backend.common.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates time-ordered UUIDs, version 7 (RFC 9562).
 *
 * <pre>
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                    unix_ts_ms (48 bits)                       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |     unix_ts_ms (cont.)        | ver=7 |    rand_a (12 bits)   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |var|                   rand_b (62 bits)                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * </pre>
 *
 * <p>Because the leading bits are a timestamp, new IDs land at the end of a B-tree index, like a
 * sequence would. Random v4 UUIDs land anywhere, which splits index pages and bloats the index.
 *
 * <p>Java 21 has no built-in v7 generator, and PostgreSQL only adds {@code uuidv7()} in version 18.
 */
public final class UuidV7 {

    /** Thread-safe; one shared instance avoids re-seeding cost. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        return generate(System.currentTimeMillis());
    }

    static UUID generate(long epochMillis) {
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        long msb = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16;   // 48-bit timestamp
        msb |= 0x7000L;                                        // version 7
        msb |= ((random[0] & 0x0FL) << 8) | (random[1] & 0xFFL); // rand_a

        long lsb = 0;
        for (int i = 2; i < 10; i++) {
            lsb = (lsb << 8) | (random[i] & 0xFFL);
        }
        lsb = (lsb & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // variant 10xx

        return new UUID(msb, lsb);
    }

    /** Extracts the creation time embedded in a v7 UUID. */
    public static long timestampMillis(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("Not a version 7 UUID: " + uuid);
        }
        return uuid.getMostSignificantBits() >>> 16;
    }
}
