package com.ecommerce.backend.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.AbstractIntegrationTest;
import io.github.bucket4j.BucketConfiguration;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;

/**
 * Two "servers" (two stores, two connections) against one Redis: a client's bucket is shared, so
 * running more instances never multiplies the limit, the Phase 1 gap this phase closes.
 */
class SharedRateLimitIT extends AbstractIntegrationTest {

    @Autowired
    private RedisConnectionDetails redis;

    private RedisBucketStore server() {
        RedisConnectionDetails.Standalone standalone = redis.getStandalone();
        return new RedisBucketStore(RedisClient.create(RedisURI.create(standalone.getHost(), standalone.getPort())));
    }

    private static BucketConfiguration fivePerMinute() {
        return BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(5).refillGreedy(5, Duration.ofMinutes(1)))
                .build();
    }

    @Test
    void twoServersShareOneBucketPerClient() {
        String key = "login|198.51.100." + UUID.randomUUID().toString().substring(0, 4);
        try (RedisBucketStore first = server(); RedisBucketStore second = server()) {
            int allowed = 0;
            for (int i = 0; i < 10; i++) {
                RedisBucketStore server = i % 2 == 0 ? first : second; // a load balancer alternating
                if (server.bucket(key, SharedRateLimitIT::fivePerMinute).tryConsume(1)) {
                    allowed++;
                }
            }
            assertThat(allowed).as("five in all, not five per server").isEqualTo(5);
        }
    }

    @Test
    void otherClientsHaveTheirOwnBuckets() {
        try (RedisBucketStore store = server()) {
            String one = "login|192.0.2." + UUID.randomUUID().toString().substring(0, 4);
            String two = one + "-other";
            for (int i = 0; i < 5; i++) {
                store.bucket(one, SharedRateLimitIT::fivePerMinute).tryConsume(1);
            }
            assertThat(store.bucket(one, SharedRateLimitIT::fivePerMinute).tryConsume(1)).isFalse();
            assertThat(store.bucket(two, SharedRateLimitIT::fivePerMinute).tryConsume(1)).isTrue();
        }
    }
}
