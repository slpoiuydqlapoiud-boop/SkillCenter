package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Optional Redis-backed runtime summary repository. It is disabled unless explicitly selected. */
@Component
@ConditionalOnProperty(name = "skill-center.runtime-summary-backend", havingValue = "redis")
public class RedisRuntimeSummaryStore implements RuntimeSummaryRepository, RuntimeSummaryBackendHealth {
    private static final RedisScript<Long> PUT_SCRIPT = new DefaultRedisScript<>("""
            local existing = redis.call('HGET', KEYS[1], ARGV[1])
            if existing then return 0 end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            redis.call('ZADD', KEYS[2], ARGV[3], ARGV[1])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String dataKey;
    private final String indexKey;

    public RedisRuntimeSummaryStore(StringRedisTemplate redis,
                                    ObjectMapper objectMapper,
                                    @Value("${skill-center.runtime-summary-redis-key:skill-center:runtime:summaries}") String key) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.dataKey = key + ":data";
        this.indexKey = key + ":index";
    }

    @Override
    public boolean putIfAbsent(RuntimeSummary summary) {
        if (summary == null || summary.eventId() == null) {
            throw new IllegalArgumentException("runtime summary and eventId are required");
        }
        String eventId = summary.eventId().toString();
        String encoded = encode(summary);
        try {
            Long inserted = redis.execute(PUT_SCRIPT, List.of(dataKey, indexKey), eventId, encoded,
                    String.valueOf(summary.occurredAt().toInstant().toEpochMilli()));
            if (Long.valueOf(1L).equals(inserted)) {
                return false;
            }
            Object existing = redis.opsForHash().get(dataKey, eventId);
            if (existing == null) {
                throw new RuntimeSummaryStore.RuntimeSummaryPersistenceException(
                        new IllegalStateException("runtime summary write was not confirmed"));
            }
            if (!summary.equals(decode(existing.toString()))) {
                throw new RuntimeSummaryConflictException("eventId already exists with different content");
            }
            return true;
        } catch (RuntimeSummaryConflictException | RuntimeSummaryStore.RuntimeSummaryPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RuntimeSummaryStore.RuntimeSummaryPersistenceException(exception);
        }
    }

    @Override
    public List<RuntimeSummary> findAll() {
        try {
            return redis.opsForHash().entries(dataKey).values().stream()
                    .map(value -> decode(value.toString()))
                    .sorted(Comparator.comparing(RuntimeSummary::occurredAt)
                            .thenComparing(RuntimeSummary::eventId))
                    .toList();
        } catch (RuntimeSummaryStore.RuntimeSummaryPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RuntimeSummaryStore.RuntimeSummaryPersistenceException(exception);
        }
    }

    @Override
    public long countBefore(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
        return findAll().stream().filter(item -> item.occurredAt().toInstant().isBefore(cutoff)).count();
    }

    @Override
    public int deleteBefore(Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
        List<String> expired = findAll().stream()
                .filter(item -> item.occurredAt().toInstant().isBefore(cutoff))
                .map(item -> item.eventId().toString()).toList();
        if (expired.isEmpty()) return 0;
        try {
            redis.opsForHash().delete(dataKey, expired.toArray());
            redis.opsForZSet().remove(indexKey, expired.toArray());
            return expired.size();
        } catch (RuntimeException exception) {
            throw new RuntimeSummaryStore.RuntimeSummaryPersistenceException(exception);
        }
    }

    @Override
    public void clear() {
        try {
            redis.delete(List.of(dataKey, indexKey));
        } catch (RuntimeException exception) {
            throw new RuntimeSummaryStore.RuntimeSummaryPersistenceException(exception);
        }
    }

    @Override
    public RuntimeSummaryReadiness readiness() {
        try {
            String response = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection ->
                    connection.ping());
            if (response != null && "PONG".equalsIgnoreCase(response.trim())) {
                return new RuntimeSummaryReadiness("redis", "READY", "RUNTIME_SUMMARY_REDIS_READY",
                        "Redis 运行摘要存储连接可用");
            }
            return unavailable();
        } catch (RuntimeException exception) {
            return unavailable();
        }
    }

    private RuntimeSummaryReadiness unavailable() {
        return new RuntimeSummaryReadiness("redis", "NOT_READY", "RUNTIME_SUMMARY_REDIS_UNAVAILABLE",
                "Redis 运行摘要存储不可用");
    }

    private String encode(RuntimeSummary summary) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (JsonProcessingException exception) {
            throw new RuntimeSummaryStore.RuntimeSummaryPersistenceException(exception);
        }
    }

    private RuntimeSummary decode(String value) {
        try {
            return objectMapper.readValue(value, RuntimeSummary.class);
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new RuntimeSummaryStore.RuntimeSummaryPersistenceException(exception);
        }
    }
}
