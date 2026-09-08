package com.huawei.skillcenter.search;

import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StreamReadOptions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Redis Streams transport for metadata-only search refresh events. */
public final class RedisSkillSearchRefreshEventBus implements SkillSearchRefreshEventBus {
    private static final String EVENT_ID = "eventId";
    private static final String SKILL_ID = "skillId";
    private static final String SOURCE_REVISION = "sourceRevision";
    private static final String REASON_CODE = "reasonCode";

    private final StringRedisTemplate redis;
    private final String stream;
    private final String group;

    public RedisSkillSearchRefreshEventBus(StringRedisTemplate redis, String stream, String group) {
        if (redis == null) throw new IllegalArgumentException("redis is required");
        this.redis = redis;
        this.stream = SkillSearchDocument.boundedRequired(stream, "stream", 128);
        this.group = SkillSearchDocument.boundedRequired(group, "group", 128);
    }

    @Override
    public void publish(SkillSearchRefreshEvent event) {
        if (event == null) throw new IllegalArgumentException("event is required");
        try {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(EVENT_ID, event.eventKey());
            values.put(SKILL_ID, event.skillId());
            values.put(SOURCE_REVISION, Long.toString(event.sourceRevision()));
            values.put(REASON_CODE, event.reasonCode());
            redis.opsForStream().add(StreamRecords.newRecord().in(stream).ofMap(values));
        } catch (RuntimeException exception) {
            throw new SkillSearchIndexPersistenceException(exception);
        }
    }

    @Override
    public List<Delivery> poll(String consumerId, int limit) {
        String boundedConsumerId = SkillSearchDocument.boundedRequired(consumerId, "consumerId", 128);
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        try {
            String consumerGroup = consumerGroupFor(boundedConsumerId);
            if (!ensureGroup(consumerGroup)) return List.of();
            List<MapRecord<String, Object, Object>> pending = read(consumerGroup, boundedConsumerId,
                    ReadOffset.from("0-0"), limit);
            if (pending.size() >= limit) return map(pending, boundedConsumerId);
            List<MapRecord<String, Object, Object>> fresh = read(consumerGroup, boundedConsumerId,
                    ReadOffset.lastConsumed(), limit - pending.size());
            return map(concat(pending, fresh), boundedConsumerId);
        } catch (SkillSearchIndexControlException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SkillSearchIndexPersistenceException(exception);
        }
    }

    @Override
    public void acknowledge(Delivery delivery) {
        if (delivery == null) throw new IllegalArgumentException("delivery is required");
        try {
            redis.opsForStream().acknowledge(stream, consumerGroupFor(delivery.consumerId()),
                    RecordId.of(delivery.messageId()));
        } catch (RuntimeException exception) {
            throw new SkillSearchIndexPersistenceException(exception);
        }
    }

    String consumerGroupFor(String consumerId) {
        String boundedConsumerId = SkillSearchDocument.boundedRequired(consumerId, "consumerId", 128);
        String suffix = sha256Hex(boundedConsumerId);
        String prefix = group + "-";
        int prefixLength = Math.min(prefix.length(), 128 - suffix.length());
        return prefix.substring(0, prefixLength) + suffix;
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean ensureGroup(String consumerGroup) {
        Boolean exists = redis.hasKey(stream);
        if (!Boolean.TRUE.equals(exists)) return false;
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.from("0-0"), consumerGroup);
        } catch (RuntimeException exception) {
            if (!isBusyGroup(exception)) throw exception;
        }
        return true;
    }

    private List<MapRecord<String, Object, Object>> read(String consumerGroup, String consumerId,
                                                         ReadOffset offset, int limit) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(consumerGroup, consumerId), StreamReadOptions.empty().count(limit),
                StreamOffset.create(stream, offset));
        return records == null ? List.of() : records;
    }

    private List<Delivery> map(List<MapRecord<String, Object, Object>> records, String consumerId) {
        return records.stream().map(record -> map(record, consumerId)).toList();
    }

    private Delivery map(MapRecord<String, Object, Object> record, String consumerId) {
        if (record == null || record.getId() == null) {
            throw new SkillSearchIndexControlException("SEARCH_INDEX_MESSAGE_INVALID");
        }
        Map<Object, Object> values = record.getValue();
        String eventId = text(values, EVENT_ID);
        String skillId = text(values, SKILL_ID);
        String sourceRevision = text(values, SOURCE_REVISION);
        String reasonCode = text(values, REASON_CODE);
        try {
            SkillSearchRefreshEvent event = new SkillSearchRefreshEvent(skillId, Long.parseLong(sourceRevision), reasonCode);
            if (!event.eventKey().equals(eventId)) {
                throw new SkillSearchIndexControlException("SEARCH_INDEX_MESSAGE_INVALID");
            }
            return new Delivery(record.getId().getValue(), consumerId, event);
        } catch (IllegalArgumentException exception) {
            throw new SkillSearchIndexControlException("SEARCH_INDEX_MESSAGE_INVALID");
        }
    }

    private String text(Map<Object, Object> values, String key) {
        if (values == null || !values.containsKey(key) || values.get(key) == null) {
            throw new SkillSearchIndexControlException("SEARCH_INDEX_MESSAGE_INVALID");
        }
        String value = values.get(key).toString();
        if (value.isBlank() || value.length() > 256) {
            throw new SkillSearchIndexControlException("SEARCH_INDEX_MESSAGE_INVALID");
        }
        return value;
    }

    private boolean isBusyGroup(RuntimeException exception) {
        String message = exception.getMessage();
        return message != null && message.toUpperCase(java.util.Locale.ROOT).contains("BUSYGROUP");
    }

    private List<MapRecord<String, Object, Object>> concat(List<MapRecord<String, Object, Object>> first,
                                                            List<MapRecord<String, Object, Object>> second) {
        java.util.ArrayList<MapRecord<String, Object, Object>> result = new java.util.ArrayList<>(first);
        result.addAll(second);
        return List.copyOf(result);
    }
}
