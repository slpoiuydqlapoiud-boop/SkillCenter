package com.huawei.skillcenter.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RedisRuntimeSummaryStoreTest {
    @Test
    void insertsSummaryAndIndexThroughOneAtomicRedisScript() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(1L).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        RedisRuntimeSummaryStore store = new RedisRuntimeSummaryStore(redis,
                new ObjectMapper().findAndRegisterModules(), "skill-center:test");

        assertThat(store.putIfAbsent(summary())).isFalse();

        verify(redis).execute(any(RedisScript.class), eq(List.of("skill-center:test:data", "skill-center:test:index")),
                any(Object[].class));
    }

    @Test
    void reportsRedisUnavailableWithoutLeakingConnectionDetails() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(null).when(redis).execute(any(org.springframework.data.redis.core.RedisCallback.class));
        RedisRuntimeSummaryStore store = new RedisRuntimeSummaryStore(redis,
                new ObjectMapper().findAndRegisterModules(), "skill-center:test");

        RuntimeSummaryReadiness readiness = store.readiness();

        assertThat(readiness.backend()).isEqualTo("redis");
        assertThat(readiness.status()).isEqualTo("NOT_READY");
        assertThat(readiness.reasonCode()).isEqualTo("RUNTIME_SUMMARY_REDIS_UNAVAILABLE");
        assertThat(readiness.toString()).doesNotContain("redis://").doesNotContain("password");
    }

    private RuntimeSummary summary() {
        return new RuntimeSummary("1.0", UUID.randomUUID(),
                OffsetDateTime.of(2026, 8, 25, 0, 0, 0, 0, ZoneOffset.UTC),
                "skill-a", "1.0.0", "success", 20, null, "production", "team-a", "codex", "trace-a");
    }
}
