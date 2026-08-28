package com.huawei.skillcenter.packageupload;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RedisResumableUploadMetadataStoreTest {
    @Test
    void createsAndCommitsThroughAtomicRedisScripts() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(1L).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        RedisResumableUploadMetadataStore store = new RedisResumableUploadMetadataStore(
                redis, "skill-center:test", 300, 10, 1024, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        assertThat(store.create("upload-1", new ResumableUploadStore.CreateRequest("demo.zip", 3, "owner")))
                .isTrue();
        store.commit("upload-1", new RedisResumableUploadMetadataStore.Reservation("RESERVED", "token"),
                "resumable/upload-1/0-2", 0, 2);

        verify(redis).execute(any(RedisScript.class), eq(List.of(
                "skill-center:test:capacity:sessions", "skill-center:test:capacity:bytes",
                "skill-center:test:index", "skill-center:test:meta:upload-1",
                "skill-center:test:chunks:upload-1", "skill-center:test:members")), any(Object[].class));
    }

    @Test
    void turnsMissingAtomicRedisResultIntoStableUnavailableError() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn(null).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        RedisResumableUploadMetadataStore store = new RedisResumableUploadMetadataStore(
                redis, "skill-center:test", 300, 10, 1024, Clock.systemUTC());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.create("upload-1",
                        new ResumableUploadStore.CreateRequest("demo.zip", 3, "owner")))
                .isInstanceOf(ResumableUploadStore.StoreException.class)
                .hasMessageContaining("UPLOAD_METADATA_UNAVAILABLE");
    }

    @Test
    void reservationScriptCanRecoverAnExpiredWriterLease() throws Exception {
        String script = script("RESERVE_SCRIPT");

        assertThat(script).contains("leaseUntil")
                .contains("state == 'WRITING'")
                .contains("ARGV[8]");
    }

    @Test
    void readinessUsesRedisPingInsteadOfADataKeyThatMayNotExistYet() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        doReturn("PONG").when(redis).execute(any(RedisCallback.class));
        RedisResumableUploadMetadataStore store = new RedisResumableUploadMetadataStore(
                redis, "skill-center:test", 300, 10, 1024, Clock.systemUTC());

        assertThat(store.readiness().status()).isEqualTo("READY");
    }

    @Test
    void abortScriptMustBeConditionalOnTheReservationToken() throws Exception {
        String script = script("ABORT_SCRIPT");

        assertThat(script).contains("pendingToken").contains("ARGV[1]");
    }

    private String script(String fieldName) throws Exception {
        java.lang.reflect.Field field = RedisResumableUploadMetadataStore.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        RedisScript<?> script = (RedisScript<?>) field.get(null);
        return script.getScriptAsString();
    }
}
