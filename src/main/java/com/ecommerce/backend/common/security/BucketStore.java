package com.ecommerce.backend.common.security;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import java.util.function.Supplier;

/**
 * Where rate-limit buckets live. In memory, every server counts on its own, so N servers let
 * N times the limit through; in Redis, all servers share one bucket per client and rule.
 * Selected with {@code app.rate-limit.store}.
 */
interface BucketStore {

    /** The bucket for {@code key}, created from {@code configuration} on first use. */
    Bucket bucket(String key, Supplier<BucketConfiguration> configuration);
}
