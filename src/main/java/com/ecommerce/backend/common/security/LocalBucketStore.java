package com.ecommerce.backend.common.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Buckets in this JVM: right for one server, for development and for tests. A bounded,
 * self-expiring map, so millions of client addresses cannot exhaust memory; a bucket untouched
 * for its longest period is full again, so forgetting it changes nothing.
 */
final class LocalBucketStore implements BucketStore {

    private final Cache<String, Bucket> buckets;
    private final TimeMeter timeMeter;

    LocalBucketStore(long maxTrackedClients, Duration longestPeriod, TimeMeter timeMeter) {
        this.buckets = Caffeine.newBuilder().maximumSize(maxTrackedClients).expireAfterAccess(longestPeriod).build();
        this.timeMeter = timeMeter;
    }

    @Override
    public Bucket bucket(String key, Supplier<BucketConfiguration> configuration) {
        return buckets.get(key, k -> {
            var builder = Bucket.builder().withCustomTimePrecision(timeMeter);
            for (var bandwidth : configuration.get().getBandwidths()) {
                builder.addLimit(bandwidth);
            }
            return builder.build();
        });
    }
}
