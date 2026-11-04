package com.ecommerce.backend.common.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class EmailOutboxRelayTest {

    @Test
    void retriesBackOffExponentiallyUpToAnHour() {
        assertThat(EmailOutboxRelay.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(EmailOutboxRelay.backoff(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(EmailOutboxRelay.backoff(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(EmailOutboxRelay.backoff(7)).isEqualTo(Duration.ofMinutes(32));
        assertThat(EmailOutboxRelay.backoff(8)).isEqualTo(Duration.ofHours(1));
        assertThat(EmailOutboxRelay.backoff(100)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void theLeaseOutlastsAWholeBatchOfSlowSends() {
        Duration worstSend = Duration.ofSeconds(15); // connect, read and write timeouts of 5 s each
        assertThat(EmailOutboxRelay.LEASE).isGreaterThan(worstSend.multipliedBy(EmailOutboxRelay.BATCH));
    }
}
