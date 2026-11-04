package com.ecommerce.backend.common.security;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Buckets in Redis, shared by every server: a client gets the limit once, however many instances
 * run behind the load balancer. Bucket4j updates each bucket with compare-and-swap, so two servers
 * consuming from it at the same moment cannot both spend the last token.
 *
 * <p>Keys expire once a bucket would be full again, so Redis holds only clients active in the
 * last period. Keys are {@code rate-limit:{rule}|{client address}}.
 */
final class RedisBucketStore implements BucketStore, AutoCloseable {

    static final String KEY_PREFIX = "rate-limit:";

    private final StatefulRedisConnection<String, byte[]> connection;
    private final LettuceBasedProxyManager<String> buckets;

    RedisBucketStore(RedisClient client) {
        this.connection = client.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
        this.buckets = Bucket4jLettuce.casBasedBuilder(connection)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(10)))
                .build();
    }

    @Override
    public Bucket bucket(String key, Supplier<BucketConfiguration> configuration) {
        return buckets.builder().build(KEY_PREFIX + key, configuration);
    }

    @Override
    public void close() {
        connection.close();
    }
}
