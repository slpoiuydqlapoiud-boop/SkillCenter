package com.huawei.skillcenter.packageupload;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalResumableUploadStoreTest {
    @Test
    void storesSequentialChunksAndReturnsAControlledCompletedPath() throws Exception {
        LocalResumableUploadStore store = new LocalResumableUploadStore(
                1024, 3, Duration.ofMinutes(5), 2, 1024, Clock.systemUTC());

        ResumableUploadStore.UploadProgress created = store.create(
                new ResumableUploadStore.CreateRequest("demo.zip", 6, "owner"));
        store.append(new ResumableUploadStore.AppendRequest(
                created.uploadId(), "owner", 0, 2, 6, "abc".getBytes(StandardCharsets.UTF_8)));
        assertThat(store.load(created.uploadId(), "owner").receivedBytes()).isEqualTo(3);

        store.append(new ResumableUploadStore.AppendRequest(
                created.uploadId(), "owner", 3, 5, 6, "def".getBytes(StandardCharsets.UTF_8)));
        ResumableUploadStore.CompletedUpload completed = store.complete(created.uploadId(), "owner");
        try {
            assertThat(Files.readString(completed.path())).isEqualTo("abcdef");
        } finally {
            store.discard(created.uploadId(), "owner");
        }
    }

    @Test
    void enforcesOwnerAndCapacityThenReleasesCapacityOnDiscard() {
        LocalResumableUploadStore store = new LocalResumableUploadStore(
                1024, 3, Duration.ofMinutes(5), 1, 6, Clock.systemUTC());
        ResumableUploadStore.UploadProgress created = store.create(
                new ResumableUploadStore.CreateRequest("demo.zip", 6, "owner"));

        assertThatThrownBy(() -> store.load(created.uploadId(), "other"))
                .isInstanceOf(ResumableUploadStore.StoreException.class)
                .hasMessageContaining("UPLOAD_FORBIDDEN");
        assertThatThrownBy(() -> store.create(
                new ResumableUploadStore.CreateRequest("other.zip", 1, "owner")))
                .isInstanceOf(ResumableUploadStore.StoreException.class)
                .hasMessageContaining("UPLOAD_CAPACITY_EXCEEDED");

        store.discard(created.uploadId(), "owner");
        ResumableUploadStore.UploadProgress replacement = store.create(
                new ResumableUploadStore.CreateRequest("other.zip", 1, "owner"));
        store.discard(replacement.uploadId(), "owner");
    }

    @Test
    void expiresIdleSessionsAndReportsLocalOnlyReadiness() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T00:00:00Z"));
        LocalResumableUploadStore store = new LocalResumableUploadStore(
                1024, 3, Duration.ofSeconds(30), 2, 1024, clock);
        ResumableUploadStore.UploadProgress created = store.create(
                new ResumableUploadStore.CreateRequest("demo.zip", 3, "owner"));
        store.append(new ResumableUploadStore.AppendRequest(
                created.uploadId(), "owner", 0, 2, 3, "zip".getBytes(StandardCharsets.UTF_8)));
        ResumableUploadStore.CompletedUpload completed = store.complete(created.uploadId(), "owner");
        assertThat(Files.exists(completed.path())).isTrue();

        clock.advance(Duration.ofSeconds(31));
        assertThat(store.cleanupExpired(clock.instant())).isEqualTo(1);
        assertThat(Files.exists(completed.path())).isFalse();
        assertThat(store.readiness().backend()).isEqualTo("local");
        assertThat(store.readiness().status()).isEqualTo("LOCAL_ONLY");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
