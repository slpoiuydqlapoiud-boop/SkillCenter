package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RedisOperationsAlertStateRepositoryTest {
    private static final Instant EVALUATED_AT = Instant.parse("2026-08-25T00:00:00Z");

    @Test
    void transitionsStateThroughOneAtomicRedisScript() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn("-1|||1|1|1787616000000")
                .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        RedisOperationsAlertStateRepository repository = new RedisOperationsAlertStateRepository(
                redis, "skill-center:test:operations:alerts:state");

        OperationsAlertStateTransition transition = repository.transition("P95_LATENCY", true, EVALUATED_AT);

        assertThat(transition.previous()).isNull();
        assertThat(transition.transitioned()).isTrue();
        assertThat(transition.current().active()).isTrue();
        assertThat(transition.current().firstTriggeredAt()).isEqualTo(EVALUATED_AT);
        verify(redis).execute(any(RedisScript.class),
                eq(List.of("skill-center:test:operations:alerts:state")), any(Object[].class));
    }

    @Test
    void reportsRedisUnavailableWithoutLeakingConnectionDetails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(null).when(redis).execute(any(org.springframework.data.redis.core.RedisCallback.class));
        RedisOperationsAlertStateRepository repository = new RedisOperationsAlertStateRepository(
                redis, "skill-center:test:operations:alerts:state");

        OperationsAlertStateReadiness readiness = repository.readiness();

        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.reasonCode()).isEqualTo("OPERATIONS_ALERT_STATE_REDIS_UNAVAILABLE");
        assertThat(readiness.toString()).doesNotContain("redis://").doesNotContain("password");
    }
}
