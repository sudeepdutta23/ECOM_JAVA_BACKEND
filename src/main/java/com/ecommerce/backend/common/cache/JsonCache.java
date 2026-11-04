package com.ecommerce.backend.common.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A read-through cache of JSON values in Redis, for public data that many requests read and few
 * change (the category list, product pages).
 *
 * <ul>
 *   <li><b>Never a source of truth.</b> Every value has a TTL, and every failure (Redis down, a value
 *       that no longer parses) falls back to the loader: the cache can make the site faster, never
 *       wrong for longer than the TTL, and never unavailable.</li>
 *   <li><b>Evict after commit.</b> Evicting before the change commits would let a concurrent read
 *       put the old value straight back. A read that started before the commit can still store the
 *       old value just after the eviction; the TTL bounds how long that lasts.</li>
 *   <li><b>No personal or per-user data</b>: keys are shared by every visitor.</li>
 * </ul>
 */
@Component
public class JsonCache {

    private static final Logger log = LoggerFactory.getLogger(JsonCache.class);
    static final String PREFIX = "cache:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final boolean enabled;

    public JsonCache(StringRedisTemplate redis, ObjectMapper mapper, @Value("${app.cache.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    /** A cache that always loads: for unit tests and for switching caching off. */
    public static JsonCache disabled() {
        return new JsonCache(null, null, false);
    }

    public <T> T get(String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        try {
            String cached = redis.opsForValue().get(PREFIX + key);
            if (cached != null) {
                return mapper.readValue(cached, type);
            }
        } catch (Exception e) {
            log.warn("Cache read failed, loading from the database: key={}", key, e);
            return loader.get();
        }
        T value = loader.get();
        try {
            redis.opsForValue().set(PREFIX + key, mapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("Cache write failed: key={}", key, e);
        }
        return value;
    }

    /** Evicts once the surrounding transaction committed, or at once outside a transaction. */
    public void evictAfterCommit(String key) {
        if (!enabled) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(key);
                }
            });
        } else {
            evict(key);
        }
    }

    private void evict(String key) {
        try {
            redis.delete(PREFIX + key);
        } catch (Exception e) {
            log.warn("Cache eviction failed; the value expires with its TTL: key={}", key, e);
        }
    }
}
