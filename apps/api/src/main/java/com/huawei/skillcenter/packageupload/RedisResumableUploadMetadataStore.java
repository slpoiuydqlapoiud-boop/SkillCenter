package com.huawei.skillcenter.packageupload;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Redis metadata, lease and capacity store for distributed resumable uploads. */
public class RedisResumableUploadMetadataStore {
    private static final DefaultRedisScript<Long> CREATE_SCRIPT = new DefaultRedisScript<>("""
            local sessions = tonumber(redis.call('GET', KEYS[1]) or '0')
            local bytes = tonumber(redis.call('GET', KEYS[2]) or '0')
            if sessions >= tonumber(ARGV[1]) or bytes + tonumber(ARGV[2]) > tonumber(ARGV[3]) then return 0 end
            redis.call('INCR', KEYS[1])
            redis.call('INCRBY', KEYS[2], ARGV[2])
            redis.call('HSET', KEYS[4], 'fileName', ARGV[4], 'totalBytes', ARGV[2], 'ownerId', ARGV[5],
              'receivedBytes', '0', 'state', 'created', 'lastActivity', ARGV[6], 'version', '0')
            redis.call('ZADD', KEYS[3], ARGV[6] + ARGV[7], ARGV[8])
            redis.call('SADD', KEYS[6], ARGV[8])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<String> RESERVE_SCRIPT = new DefaultRedisScript<>("""
            local state = redis.call('HGET', KEYS[1], 'state')
            if not state then return 'NOT_FOUND' end
            if ARGV[1] ~= '' and redis.call('HGET', KEYS[1], 'ownerId') ~= ARGV[1] then return 'FORBIDDEN' end
            local received = tonumber(redis.call('HGET', KEYS[1], 'receivedBytes') or '-1')
            if tonumber(ARGV[3]) ~= tonumber(redis.call('HGET', KEYS[1], 'totalBytes') or '-2') then return 'TOTAL_MISMATCH' end
            local now = tonumber(ARGV[6])
            local leaseUntil = tonumber(redis.call('HGET', KEYS[1], 'leaseUntil') or '0')
            if state == 'WRITING' and leaseUntil > now then return 'BUSY' end
            if received ~= tonumber(ARGV[2]) then
              if redis.call('HGET', KEYS[1], 'lastStart') == ARGV[2] and redis.call('HGET', KEYS[1], 'lastEnd') == ARGV[4] then return 'IDEMPOTENT' end
              return 'OFFSET_INVALID'
            end
            redis.call('HSET', KEYS[1], 'state', 'WRITING', 'pendingStart', ARGV[2], 'pendingEnd', ARGV[4],
              'pendingToken', ARGV[5], 'lastActivity', ARGV[6], 'leaseUntil', ARGV[6] + ARGV[8])
            return 'RESERVED'
            """, String.class);

    private static final DefaultRedisScript<Long> COMMIT_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'state') ~= 'WRITING' then return 0 end
            if redis.call('HGET', KEYS[1], 'pendingToken') ~= ARGV[1] then return 0 end
            local total = tonumber(redis.call('HGET', KEYS[1], 'totalBytes'))
            local endOffset = tonumber(ARGV[3])
            local received = endOffset + 1
            local nextState = 'uploading'
            if received == total then nextState = 'ready' end
            redis.call('HSET', KEYS[1], 'receivedBytes', received, 'state', nextState,
              'lastStart', ARGV[2], 'lastEnd', ARGV[3], 'lastActivity', ARGV[4])
            redis.call('HDEL', KEYS[1], 'pendingStart', 'pendingEnd', 'pendingToken', 'leaseUntil')
            redis.call('RPUSH', KEYS[2], ARGV[5])
            redis.call('ZADD', KEYS[3], ARGV[4] + ARGV[6], ARGV[7])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> TOUCH_SCRIPT = new DefaultRedisScript<>("""
            if not redis.call('HGET', KEYS[1], 'state') then return 0 end
            redis.call('HSET', KEYS[1], 'lastActivity', ARGV[1])
            redis.call('ZADD', KEYS[2], ARGV[1] + ARGV[2], ARGV[3])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> ABORT_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'state') ~= 'WRITING' then return 0 end
            if redis.call('HGET', KEYS[1], 'pendingToken') ~= ARGV[1] then return 0 end
            redis.call('HSET', KEYS[1], 'state', 'uploading', 'lastActivity', ARGV[2])
            redis.call('HDEL', KEYS[1], 'pendingStart', 'pendingEnd', 'pendingToken', 'leaseUntil')
            redis.call('ZADD', KEYS[2], ARGV[2] + ARGV[3], ARGV[4])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> DISCARD_SCRIPT = new DefaultRedisScript<>("""
            local total = tonumber(redis.call('HGET', KEYS[4], 'totalBytes') or '0')
            if total == 0 then return 0 end
            redis.call('DECR', KEYS[1])
            redis.call('DECRBY', KEYS[2], total)
            redis.call('ZREM', KEYS[3], ARGV[1])
            redis.call('SREM', KEYS[5], ARGV[1])
            redis.call('DEL', KEYS[4], KEYS[6])
            return total
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final int ttlSeconds;
    private final int maxActiveSessions;
    private final long maxActiveBytes;
    private final Clock clock;

    public RedisResumableUploadMetadataStore(StringRedisTemplate redis, String keyPrefix, int ttlSeconds,
                                             int maxActiveSessions, long maxActiveBytes, Clock clock) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "skill-center:uploads" : keyPrefix.trim();
        this.ttlSeconds = Math.max(1, ttlSeconds);
        this.maxActiveSessions = maxActiveSessions;
        this.maxActiveBytes = maxActiveBytes;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public boolean create(String uploadId, ResumableUploadStore.CreateRequest request) {
        long now = clock.instant().getEpochSecond();
        try {
            Long result = redis.execute(CREATE_SCRIPT,
                    List.of(capacitySessionsKey(), capacityBytesKey(), indexKey(), metadataKey(uploadId),
                            chunksKey(uploadId), membersKey()),
                    String.valueOf(maxActiveSessions), String.valueOf(request.totalBytes()),
                    String.valueOf(maxActiveBytes), request.fileName(), normalizeOwner(request.ownerId()),
                    String.valueOf(now), String.valueOf(ttlSeconds), uploadId);
            if (result == null) throw unavailable();
            return result == 1L;
        } catch (ResumableUploadStore.StoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResumableUploadStore.StoreException("UPLOAD_METADATA_UNAVAILABLE", "上传元数据存储不可用", exception);
        }
    }

    public Metadata load(String uploadId) {
        try {
            Map<Object, Object> values = redis.opsForHash().entries(metadataKey(uploadId));
            if (values == null || values.isEmpty()) throw notFound();
            return Metadata.from(values);
        } catch (ResumableUploadStore.StoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResumableUploadStore.StoreException("UPLOAD_METADATA_UNAVAILABLE", "上传元数据存储不可用", exception);
        }
    }

    public Reservation reserve(String uploadId, String ownerId, long start, long end, long totalBytes) {
        String token = uploadId + ":" + start + "-" + end;
        long now = clock.instant().getEpochSecond();
        long leaseSeconds = Math.max(1, ttlSeconds / 2L);
        try {
            String result = redis.execute(RESERVE_SCRIPT, List.of(metadataKey(uploadId)), ownerId == null ? "" : ownerId,
                    String.valueOf(start), String.valueOf(totalBytes), String.valueOf(end), token,
                    String.valueOf(now), String.valueOf(ttlSeconds), String.valueOf(leaseSeconds));
            if (result == null) throw unavailable();
            return new Reservation(result, token);
        } catch (ResumableUploadStore.StoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResumableUploadStore.StoreException("UPLOAD_METADATA_UNAVAILABLE", "上传元数据存储不可用", exception);
        }
    }

    public void commit(String uploadId, Reservation reservation, String chunkKey, long start, long end) {
        safe(() -> {
            long now = clock.instant().getEpochSecond();
            Long result = redis.execute(COMMIT_SCRIPT, List.of(metadataKey(uploadId), chunksKey(uploadId), indexKey()),
                    reservation.token(), String.valueOf(start), String.valueOf(end), String.valueOf(now), chunkKey,
                    String.valueOf(ttlSeconds), uploadId);
            if (result == null || result != 1L) throw new ResumableUploadStore.StoreException(
                    "UPLOAD_LEASE_LOST", "上传分片提交租约已失效");
            return null;
        });
    }

    public void abort(String uploadId, Reservation reservation) {
        safe(() -> {
            long now = clock.instant().getEpochSecond();
            Long result = redis.execute(ABORT_SCRIPT, List.of(metadataKey(uploadId), indexKey()), reservation.token(),
                    String.valueOf(now), String.valueOf(ttlSeconds), uploadId);
            if (result == null) throw unavailable();
            return null;
        });
    }

    public void touch(String uploadId) {
        safe(() -> {
            long now = clock.instant().getEpochSecond();
            Long result = redis.execute(TOUCH_SCRIPT, List.of(metadataKey(uploadId), indexKey()),
                    String.valueOf(now), String.valueOf(ttlSeconds), uploadId);
            if (result == null) throw unavailable();
            if (result == 0L) throw notFound();
            return null;
        });
    }

    public List<String> chunkKeys(String uploadId) {
        return safe(() -> {
            List<String> keys = redis.opsForList().range(chunksKey(uploadId), 0, -1);
            return keys == null ? List.of() : List.copyOf(keys);
        });
    }

    public void markCompleted(String uploadId) {
        safe(() -> {
            redis.opsForHash().put(metadataKey(uploadId), "state", "completed");
            return null;
        });
    }

    public void discard(String uploadId, String ownerId) {
        safe(() -> {
            Metadata metadata = load(uploadId);
            if (ownerId != null && !normalizeOwner(ownerId).equals(metadata.ownerId())) {
                throw new ResumableUploadStore.StoreException("UPLOAD_FORBIDDEN", "上传会话不属于当前用户");
            }
            Long result = redis.execute(DISCARD_SCRIPT,
                    List.of(capacitySessionsKey(), capacityBytesKey(), indexKey(), metadataKey(uploadId), membersKey(), chunksKey(uploadId)),
                    uploadId);
            if (result == null) throw unavailable();
            return null;
        });
    }

    public int cleanupExpired(Instant now) {
        Set<String> ids = expiredUploadIds(now);
        if (ids == null) return 0;
        int removed = 0;
        for (String uploadId : new ArrayList<>(ids)) {
            try {
                discard(uploadId, null);
                removed++;
            } catch (ResumableUploadStore.StoreException ignored) {
                // An expired hash may already have disappeared; the next pass can remove its index entry.
            }
        }
        return removed;
    }

    public Set<String> expiredUploadIds(Instant now) {
        return safe(() -> {
            Set<String> ids = redis.opsForZSet().rangeByScore(indexKey(), 0, now.getEpochSecond());
            return ids == null ? Set.of() : Set.copyOf(ids);
        });
    }

    public ResumableUploadStore.Readiness readiness() {
        try {
            String pong = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping());
            return new ResumableUploadStore.Readiness("redis", "PONG".equalsIgnoreCase(pong) ? "READY" : "NOT_READY");
        } catch (RuntimeException unavailable) {
            return new ResumableUploadStore.Readiness("redis", "NOT_READY");
        }
    }

    private String normalizeOwner(String ownerId) {
        return ownerId == null || ownerId.isBlank() ? "local-user" : ownerId;
    }

    private ResumableUploadStore.StoreException unavailable() {
        return new ResumableUploadStore.StoreException("UPLOAD_METADATA_UNAVAILABLE", "上传元数据存储不可用");
    }

    private ResumableUploadStore.StoreException notFound() {
        return new ResumableUploadStore.StoreException("UPLOAD_NOT_FOUND", "上传会话不存在或已过期");
    }

    private <T> T safe(RedisCall<T> operation) {
        try {
            return operation.run();
        } catch (ResumableUploadStore.StoreException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResumableUploadStore.StoreException("UPLOAD_METADATA_UNAVAILABLE", "上传元数据存储不可用", exception);
        }
    }

    private String metadataKey(String id) { return keyPrefix + ":meta:" + id; }
    private String chunksKey(String id) { return keyPrefix + ":chunks:" + id; }
    private String capacitySessionsKey() { return keyPrefix + ":capacity:sessions"; }
    private String capacityBytesKey() { return keyPrefix + ":capacity:bytes"; }
    private String indexKey() { return keyPrefix + ":index"; }
    private String membersKey() { return keyPrefix + ":members"; }

    @FunctionalInterface
    private interface RedisCall<T> {
        T run();
    }

    public record Reservation(String result, String token) {
        public boolean reserved() { return "RESERVED".equals(result); }
        public boolean idempotent() { return "IDEMPOTENT".equals(result); }
    }

    public record Metadata(String fileName, long totalBytes, String ownerId, long receivedBytes,
                           String state, long lastActivity, long version) {
        private static Metadata from(Map<Object, Object> values) {
            return new Metadata(string(values, "fileName"), number(values, "totalBytes"), string(values, "ownerId"),
                    number(values, "receivedBytes"), string(values, "state"), number(values, "lastActivity"),
                    number(values, "version"));
        }

        private static String string(Map<Object, Object> values, String key) {
            return String.valueOf(values.getOrDefault(key, ""));
        }

        private static long number(Map<Object, Object> values, String key) {
            return Long.parseLong(string(values, key));
        }
    }
}
