package com.ecommerce.backend.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JsonCacheTest {

    record Item(String name, int count) {
    }

    private static final TypeReference<List<Item>> ITEMS = new TypeReference<>() {
    };

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final JsonCache cache = new JsonCache(redis, new ObjectMapper(), true);
    private final AtomicInteger loads = new AtomicInteger();

    {
        when(redis.opsForValue()).thenReturn(values);
    }

    private List<Item> load() {
        loads.incrementAndGet();
        return List.of(new Item("mug", 2));
    }

    @Test
    void aMissLoadsAndStoresWithTheTtl() {
        assertThat(cache.get("k", Duration.ofMinutes(5), ITEMS, this::load)).containsExactly(new Item("mug", 2));

        verify(values).set(eq("cache:k"), eq("[{\"name\":\"mug\",\"count\":2}]"), eq(Duration.ofMinutes(5)));
        assertThat(loads).hasValue(1);
    }

    @Test
    void aHitIsReadBackIntoRecordsWithoutLoading() {
        when(values.get("cache:k")).thenReturn("[{\"name\":\"cup\",\"count\":1}]");

        assertThat(cache.get("k", Duration.ofMinutes(5), ITEMS, this::load)).containsExactly(new Item("cup", 1));
        assertThat(loads).hasValue(0);
    }

    @Test
    void redisDownMeansTheDatabaseAnswersNeverAnError() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.get("k", Duration.ofMinutes(5), ITEMS, this::load)).hasSize(1);
        assertThat(loads).hasValue(1);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void anUnreadableValueIsReloaded() {
        when(values.get("cache:k")).thenReturn("{not json");

        assertThat(cache.get("k", Duration.ofMinutes(5), ITEMS, this::load)).hasSize(1);
        assertThat(loads).hasValue(1);
    }

    @Test
    void evictionWaitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.evictAfterCommit("k");
            verify(redis, never()).delete(anyString());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(redis).delete("cache:k");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aDisabledCacheAlwaysLoads() {
        JsonCache off = JsonCache.disabled();

        off.get("k", Duration.ofMinutes(5), ITEMS, this::load);
        off.get("k", Duration.ofMinutes(5), ITEMS, this::load);
        off.evictAfterCommit("k");

        assertThat(loads).hasValue(2);
    }
}
