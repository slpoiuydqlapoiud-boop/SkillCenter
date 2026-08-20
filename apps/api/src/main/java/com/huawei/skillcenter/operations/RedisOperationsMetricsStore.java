package com.huawei.skillcenter.operations;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RedisOperationsMetricsStore implements OperationsMetricsStore {
    private static final int LATENCY_BUCKETS = 8;
    private final Client client;

    public RedisOperationsMetricsStore(StringRedisTemplate redisTemplate, String keyPrefix, long ttlSeconds) {
        this(new SpringDataRedisClient(redisTemplate, keyPrefix, Math.max(60, ttlSeconds)));
    }

    RedisOperationsMetricsStore(Client client) {
        this.client = client;
    }

    @Override
    public List<Bucket> load() {
        return client.load();
    }

    @Override
    public void save(List<Bucket> buckets) {
        client.clear();
        merge(buckets);
    }

    @Override
    public void merge(List<Bucket> buckets) {
        if (buckets == null) {
            return;
        }
        buckets.forEach(client::merge);
    }

    @Override
    public boolean sharedReads() {
        return true;
    }

    @Override
    public String status() {
        return client.status();
    }

    interface Client {
        List<Bucket> load();

        void merge(Bucket delta);

        void clear();

        String status();
    }

    private static final class SpringDataRedisClient implements Client {
        private static final DefaultRedisScript<Long> MERGE_SCRIPT = new DefaultRedisScript<>("""
                local bucket = KEYS[1]
                local index = KEYS[2]
                local ttl = tonumber(ARGV[1])
                local max_ms = tonumber(ARGV[2])
                redis.call('HINCRBY', bucket, 'total', tonumber(ARGV[3]))
                redis.call('HINCRBY', bucket, 'successes', tonumber(ARGV[4]))
                redis.call('HINCRBY', bucket, 'clientErrors', tonumber(ARGV[5]))
                redis.call('HINCRBY', bucket, 'serverErrors', tonumber(ARGV[6]))
                local current_max = tonumber(redis.call('HGET', bucket, 'maxMs') or '0')
                if max_ms > current_max then redis.call('HSET', bucket, 'maxMs', max_ms) end
                for index = 1, 8 do
                    redis.call('HINCRBY', bucket, 'latency_' .. (index - 1), tonumber(ARGV[6 + index]))
                end
                local security_count = tonumber(ARGV[15])
                local cursor = 16
                for index = 1, security_count do
                    local field = ARGV[cursor]
                    local value = tonumber(ARGV[cursor + 1])
                    redis.call('HINCRBY', bucket, field, value)
                    cursor = cursor + 2
                end
                redis.call('SADD', index, bucket)
                redis.call('EXPIRE', bucket, ttl)
                redis.call('EXPIRE', index, ttl)
                return 1
                """, Long.class);

        private final StringRedisTemplate redis;
        private final String bucketPrefix;
        private final String indexKey;
        private final long ttlSeconds;
        private volatile String status = "ENABLED";

        private SpringDataRedisClient(StringRedisTemplate redis, String keyPrefix, long ttlSeconds) {
            this.redis = redis;
            this.bucketPrefix = keyPrefix + ":bucket:";
            this.indexKey = keyPrefix + ":index";
            this.ttlSeconds = ttlSeconds;
        }

        @Override
        public List<Bucket> load() {
            try {
                Set<String> keys = redis.opsForSet().members(indexKey);
                if (keys == null || keys.isEmpty()) {
                    status = "ENABLED";
                    return List.of();
                }
                List<Bucket> buckets = new ArrayList<>();
                for (String key : keys) {
                    Map<Object, Object> fields = redis.opsForHash().entries(key);
                    if (fields.isEmpty()) {
                        continue;
                    }
                    Bucket bucket = fromHash(key, fields);
                    if (bucket != null) {
                        buckets.add(bucket);
                    }
                }
                status = "ENABLED";
                return buckets;
            } catch (RuntimeException exception) {
                status = "DEGRADED";
                return List.of();
            }
        }

        @Override
        public void merge(Bucket delta) {
            try {
                List<String> args = new ArrayList<>();
                args.add(Long.toString(ttlSeconds));
                args.add(Long.toString(delta.maxMs()));
                args.add(Long.toString(delta.total()));
                args.add(Long.toString(delta.successes()));
                args.add(Long.toString(delta.clientErrors()));
                args.add(Long.toString(delta.serverErrors()));
                for (long count : delta.latencyCounts()) {
                    args.add(Long.toString(count));
                }
                List<String> security = new ArrayList<>();
                delta.securityEvents().forEach((key, value) -> {
                    security.add("security." + key);
                    security.add(Long.toString(value));
                });
                args.add(Integer.toString(security.size() / 2));
                args.addAll(security);
                redis.execute(MERGE_SCRIPT,
                        List.of(bucketPrefix + delta.bucketKey(), indexKey), args.toArray(String[]::new));
                status = "ENABLED";
            } catch (RuntimeException exception) {
                status = "DEGRADED";
            }
        }

        @Override
        public void clear() {
            try {
                Set<String> keys = redis.opsForSet().members(indexKey);
                if (keys != null && !keys.isEmpty()) {
                    redis.delete(keys);
                }
                redis.delete(indexKey);
                status = "ENABLED";
            } catch (RuntimeException exception) {
                status = "DEGRADED";
            }
        }

        @Override
        public String status() {
            return status;
        }

        private Bucket fromHash(String key, Map<Object, Object> fields) {
            try {
                long bucketKey = Long.parseLong(key.substring(bucketPrefix.length()));
                long[] latencyCounts = new long[LATENCY_BUCKETS];
                Map<String, Long> securityEvents = new HashMap<>();
                for (int index = 0; index < LATENCY_BUCKETS; index++) {
                    latencyCounts[index] = number(fields.get("latency_" + index));
                }
                fields.forEach((field, value) -> {
                    String name = field.toString();
                    if (name.startsWith("security.")) {
                        securityEvents.put(name.substring("security.".length()), number(value));
                    }
                });
                return new Bucket(bucketKey, number(fields.get("total")), number(fields.get("successes")),
                        number(fields.get("clientErrors")), number(fields.get("serverErrors")),
                        number(fields.get("maxMs")), latencyCounts, securityEvents);
            } catch (RuntimeException exception) {
                return null;
            }
        }

        private long number(Object value) {
            return value == null ? 0 : Long.parseLong(value.toString());
        }
    }
}
