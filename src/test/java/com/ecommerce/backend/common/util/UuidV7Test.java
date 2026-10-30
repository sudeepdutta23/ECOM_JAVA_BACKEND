package com.ecommerce.backend.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @Test
    void hasVersion7AndRfcVariant() {
        UUID id = UuidV7.generate();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2); // IETF variant, bits 10xx
    }

    @Test
    void embedsTheGivenTimestamp() {
        long millis = 1_767_225_600_000L; // 2026-01-01T00:00:00Z

        assertThat(UuidV7.timestampMillis(UuidV7.generate(millis))).isEqualTo(millis);
    }

    @Test
    void laterTimestampsSortAfterEarlierOnes() {
        UUID earlier = UuidV7.generate(1_000_000L);
        UUID later = UuidV7.generate(1_000_001L);

        assertThat(earlier.toString()).isLessThan(later.toString()); // byte order, as PostgreSQL sorts
        assertThat(earlier).isLessThan(later);                      // Java UUID order
    }

    @Test
    void idsInTheSameMillisecondAreStillUnique() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(UuidV7.generate(42L));
        }

        assertThat(ids).hasSize(10_000);
    }

    @Test
    void rejectsTimestampExtractionFromOtherVersions() {
        assertThatThrownBy(() -> UuidV7.timestampMillis(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
