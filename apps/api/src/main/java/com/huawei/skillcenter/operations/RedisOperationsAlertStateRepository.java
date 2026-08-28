package com.huawei.skillcenter.operations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Redis-backed alert state with an atomic read/transition/write script. */
@Component
@ConditionalOnProperty(name = "skill-center.operations.alert-state-backend", havingValue = "redis")
public class RedisOperationsAlertStateRepository implements OperationsAlertStateRepository {
    private static final RedisScript<String> TRANSITION_SCRIPT = new DefaultRedisScript<>("""
            local existing = redis.call('HGET', KEYS[1], ARGV[1])
            local previousActive = '-1'
            local previousFirst = ''
            local previousLast = ''
            if existing then
                local firstSeparator = string.find(existing, '|', 1, true)
                local secondSeparator = string.find(existing, '|', firstSeparator + 1, true)
                previousActive = string.sub(existing, 1, firstSeparator - 1)
                previousFirst = string.sub(existing, firstSeparator + 1, secondSeparator - 1)
                previousLast = string.sub(existing, secondSeparator + 1)
            end
            local transitioned = '0'
            if previousActive == '-1' then
                if ARGV[2] == '1' then transitioned = '1' end
            elseif previousActive ~= ARGV[2] then
                transitioned = '1'
            end
            local currentFirst = previousFirst
            if ARGV[2] == '1' and previousActive ~= '1' then
                currentFirst = ARGV[3]
            elseif ARGV[2] == '0' and previousActive == '-1' then
                currentFirst = ''
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2] .. '|' .. currentFirst .. '|' .. ARGV[3])
            return previousActive .. '|' .. previousFirst .. '|' .. previousLast .. '|'
                    .. transitioned .. '|' .. ARGV[2] .. '|' .. currentFirst
            """, String.class);

    private final StringRedisTemplate redis;
    private final String stateKey;

    public RedisOperationsAlertStateRepository(StringRedisTemplate redis,
                                               @Value("${skill-center.operations.alerts.state-key:skill-center:operations:alerts:state}")
                                               String stateKey) {
        this.redis = redis;
        this.stateKey = stateKey;
    }

    @Override
    public OperationsAlertStateTransition transition(String key, boolean active, Instant evaluatedAt) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        if (evaluatedAt == null) throw new IllegalArgumentException("evaluatedAt is required");
        try {
            String encoded = redis.execute(TRANSITION_SCRIPT, List.of(stateKey), key,
                    active ? "1" : "0", String.valueOf(evaluatedAt.toEpochMilli()));
            return decode(encoded, evaluatedAt);
        } catch (OperationsAlertStatePersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new OperationsAlertStatePersistenceException(exception);
        }
    }

    @Override
    public OperationsAlertStateReadiness readiness() {
        try {
            String response = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection ->
                    connection.ping());
            if (response != null && "PONG".equalsIgnoreCase(response.trim())) {
                return new OperationsAlertStateReadiness("redis", "READY",
                        "OPERATIONS_ALERT_STATE_REDIS_READY", "Redis 告警状态存储连接可用");
            }
            return unavailable();
        } catch (RuntimeException exception) {
            return unavailable();
        }
    }

    private OperationsAlertStateTransition decode(String encoded, Instant evaluatedAt) {
        if (encoded == null) throw new IllegalStateException("Redis alert state transition was not confirmed");
        String[] values = encoded.split("\\|", -1);
        if (values.length != 6) throw new IllegalStateException("Invalid Redis alert state transition");
        OperationsAlertState previous = "-1".equals(values[0]) ? null : new OperationsAlertState(
                "1".equals(values[0]), parseInstant(values[1]), parseInstant(values[2]));
        OperationsAlertState current = new OperationsAlertState(
                "1".equals(values[4]), parseInstant(values[5]), evaluatedAt);
        return new OperationsAlertStateTransition(previous, current, "1".equals(values[3]));
    }

    private Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.ofEpochMilli(Long.parseLong(value));
    }

    private OperationsAlertStateReadiness unavailable() {
        return new OperationsAlertStateReadiness("redis", "NOT_READY",
                "OPERATIONS_ALERT_STATE_REDIS_UNAVAILABLE", "Redis 告警状态存储不可用");
    }
}
