package com.huawei.skillcenter.packageupload;

import com.huawei.skillcenter.distribution.S3ObjectClient;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistributedResumableUploadStoreTest {
    @Test
    void reservesObjectCommitsMetadataAndAssemblesAcrossStoreBoundaries() throws Exception {
        RedisResumableUploadMetadataStore metadata = mock(RedisResumableUploadMetadataStore.class);
        S3ObjectClient objects = mock(S3ObjectClient.class);
        DistributedResumableUploadStore store = new DistributedResumableUploadStore(
                1024, 3, metadata, objects, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        String uploadId = "upload-1";
        ResumableUploadStore.CreateRequest create = new ResumableUploadStore.CreateRequest("demo.zip", 3, "owner");
        RedisResumableUploadMetadataStore.Metadata ready = new RedisResumableUploadMetadataStore.Metadata(
                "demo.zip", 3, "owner", 3, "ready", 0, 1);

        when(metadata.create(any(), eq(create))).thenReturn(true);
        when(metadata.load(any())).thenReturn(ready);
        when(metadata.reserve(uploadId, null, 0, 2, 3))
                .thenReturn(new RedisResumableUploadMetadataStore.Reservation("RESERVED", "upload-1:0-2"));
        when(objects.putIfAbsent(eq("resumable/upload-1/0-2"), any()))
                .thenReturn(new S3ObjectClient.PutResult(true, 200));
        when(metadata.chunkKeys(uploadId)).thenReturn(List.of("resumable/upload-1/0-2"));
        when(objects.get("resumable/upload-1/0-2"))
                .thenReturn(Optional.of("zip".getBytes(StandardCharsets.UTF_8)));

        ResumableUploadStore.UploadProgress created = store.create(create);
        assertThat(created.uploadId()).isNotBlank();
        store.append(new ResumableUploadStore.AppendRequest(uploadId, null, 0, 2, 3,
                "zip".getBytes(StandardCharsets.UTF_8)));
        ResumableUploadStore.CompletedUpload completed = store.complete(uploadId, "owner");

        try {
            assertThat(java.nio.file.Files.readString(completed.path())).isEqualTo("zip");
            verify(metadata).commit(uploadId, new RedisResumableUploadMetadataStore.Reservation(
                    "RESERVED", "upload-1:0-2"), "resumable/upload-1/0-2", 0, 2);
        } finally {
            java.nio.file.Files.deleteIfExists(completed.path());
        }
    }

    @Test
    void failsClosedWhenTheObjectPutCannotBeCommitted() {
        RedisResumableUploadMetadataStore metadata = mock(RedisResumableUploadMetadataStore.class);
        S3ObjectClient objects = mock(S3ObjectClient.class);
        DistributedResumableUploadStore store = new DistributedResumableUploadStore(
                1024, 3, metadata, objects, Clock.systemUTC());
        when(metadata.reserve("upload-1", null, 0, 2, 3))
                .thenReturn(new RedisResumableUploadMetadataStore.Reservation("RESERVED", "token"));
        when(objects.putIfAbsent(any(), any())).thenThrow(new IllegalStateException("down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.append(
                        new ResumableUploadStore.AppendRequest("upload-1", null, 0, 2, 3,
                                "zip".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ResumableUploadStore.StoreException.class)
                .hasMessageContaining("UPLOAD_STORAGE_UNAVAILABLE");
        verify(metadata).abort(eq("upload-1"), any());
    }

    @Test
    void rejectsAnIdempotentRangeWhenTheExistingObjectHasDifferentBytes() {
        RedisResumableUploadMetadataStore metadata = mock(RedisResumableUploadMetadataStore.class);
        S3ObjectClient objects = mock(S3ObjectClient.class);
        DistributedResumableUploadStore store = new DistributedResumableUploadStore(
                1024, 3, metadata, objects, Clock.systemUTC());
        when(metadata.reserve("upload-1", null, 0, 2, 3))
                .thenReturn(new RedisResumableUploadMetadataStore.Reservation("IDEMPOTENT", "token"));
        when(objects.get("resumable/upload-1/0-2"))
                .thenReturn(Optional.of("bad".getBytes(StandardCharsets.UTF_8)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.append(
                        new ResumableUploadStore.AppendRequest("upload-1", null, 0, 2, 3,
                                "zip".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ResumableUploadStore.StoreException.class)
                .hasMessageContaining("UPLOAD_CHUNK_CONFLICT");
    }

    @Test
    void readinessFailsClosedWhenRedisIsReadyButObjectStorageIsNotReady() {
        RedisResumableUploadMetadataStore metadata = mock(RedisResumableUploadMetadataStore.class);
        S3ObjectClient objects = mock(S3ObjectClient.class);
        when(metadata.readiness()).thenReturn(new ResumableUploadStore.Readiness("redis", "READY"));
        when(objects.readyForUse()).thenReturn(false);
        DistributedResumableUploadStore store = new DistributedResumableUploadStore(
                1024, 3, metadata, objects, Clock.systemUTC());

        assertThat(store.readiness().status()).isEqualTo("NOT_READY");
    }
}
