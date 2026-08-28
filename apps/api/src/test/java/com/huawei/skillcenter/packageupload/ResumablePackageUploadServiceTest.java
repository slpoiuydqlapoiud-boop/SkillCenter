package com.huawei.skillcenter.packageupload;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumablePackageUploadServiceTest {
    @Test
    void acceptsSequentialChunksAndCanResumeFromProgress() throws Exception {
        ResumablePackageUploadService service = new ResumablePackageUploadService(1024, 3);

        ResumablePackageUploadService.UploadProgress created = service.create("demo.zip", 6);
        assertThat(created.receivedBytes()).isZero();
        assertThat(created.status()).isEqualTo("created");

        service.append(created.uploadId(), 0, 2, 6, "abc".getBytes(StandardCharsets.UTF_8));
        ResumablePackageUploadService.UploadProgress resumed = service.progress(created.uploadId());
        assertThat(resumed.receivedBytes()).isEqualTo(3);
        assertThat(resumed.status()).isEqualTo("uploading");

        service.append(created.uploadId(), 3, 5, 6, "def".getBytes(StandardCharsets.UTF_8));
        ResumablePackageUploadService.CompletedUpload completed = service.complete(created.uploadId());
        try {
            assertThat(Files.readString(completed.path())).isEqualTo("abcdef");
            assertThat(service.progress(created.uploadId()).status()).isEqualTo("completed");
        } finally {
            Files.deleteIfExists(completed.path());
            service.discard(created.uploadId());
        }
    }

    @Test
    void rejectsOutOfOrderAndOversizedChunksWithoutAdvancingProgress() {
        ResumablePackageUploadService service = new ResumablePackageUploadService(1024, 3);
        ResumablePackageUploadService.UploadProgress created = service.create("demo.zip", 6);

        assertThatThrownBy(() -> service.append(created.uploadId(), 1, 3, 6, "bcd".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ResumablePackageUploadException.class)
                .hasMessageContaining("UPLOAD_OFFSET_INVALID");
        assertThatThrownBy(() -> service.append(created.uploadId(), 0, 3, 6, "abcd".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ResumablePackageUploadException.class)
                .hasMessageContaining("UPLOAD_CHUNK_TOO_LARGE");
        assertThat(service.progress(created.uploadId()).receivedBytes()).isZero();
        service.discard(created.uploadId());
    }

    @Test
    void requiresZipNameAndCompletePayload() {
        ResumablePackageUploadService service = new ResumablePackageUploadService(1024, 3);

        assertThatThrownBy(() -> service.create("demo.tar", 4))
                .isInstanceOf(ResumablePackageUploadException.class)
                .hasMessageContaining("UPLOAD_FILE_INVALID");
        ResumablePackageUploadService.UploadProgress created = service.create("demo.zip", 4);
        assertThatThrownBy(() -> service.complete(created.uploadId()))
                .isInstanceOf(ResumablePackageUploadException.class)
                .hasMessageContaining("UPLOAD_INCOMPLETE");
        service.discard(created.uploadId());
    }

    @Test
    void preventsAnotherActorFromUsingTheUploadSession() {
        ResumablePackageUploadService service = new ResumablePackageUploadService(1024, 3);
        ResumablePackageUploadService.UploadProgress created = service.create("demo.zip", 3, "owner");

        assertThatThrownBy(() -> service.requireOwner(created.uploadId(), "other"))
                .isInstanceOf(ResumablePackageUploadException.class)
                .hasMessageContaining("UPLOAD_FORBIDDEN");
        service.discard(created.uploadId());
    }

    @Test
    void expiresIdleSessionsAndDeletesCompletedTemporaryFiles() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T00:00:00Z"));
        ResumablePackageUploadService service = new ResumablePackageUploadService(
                1024, 3, Duration.ofSeconds(30), 4, 1024, clock);

        ResumablePackageUploadService.UploadProgress created = service.create("demo.zip", 3);
        service.append(created.uploadId(), 0, 2, 3, "zip".getBytes(StandardCharsets.UTF_8));
        ResumablePackageUploadService.CompletedUpload completed = service.complete(created.uploadId());
        assertThat(Files.exists(completed.path())).isTrue();

        clock.advance(Duration.ofSeconds(31));
        assertThat(service.cleanupExpiredSessions()).isEqualTo(1);
        assertThat(Files.exists(completed.path())).isFalse();
        assertThatThrownBy(() -> service.progress(created.uploadId()))
                .isInstanceOf(ResumablePackageUploadException.class)
                .hasMessageContaining("UPLOAD_NOT_FOUND");
    }

    @Test
    void rejectsNewSessionsWhenActiveSessionCapacityIsReachedAndReleasesItOnDiscard() {
        ResumablePackageUploadService service = new ResumablePackageUploadService(
                1024, 3, Duration.ofMinutes(5), 1, 6, Clock.systemUTC());

        ResumablePackageUploadService.UploadProgress created = service.create("demo.zip", 6);
        try {
            assertThatThrownBy(() -> service.create("other.zip", 1))
                    .isInstanceOf(ResumablePackageUploadException.class)
                    .hasMessageContaining("UPLOAD_CAPACITY_EXCEEDED");
            service.discard(created.uploadId());
            assertThat(service.create("other.zip", 1).totalBytes()).isEqualTo(1);
        } finally {
            service.discard(created.uploadId());
        }
    }

    @Test
    void progressActivityRenewsTheIdleSessionBeforeItExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T00:00:00Z"));
        ResumablePackageUploadService service = new ResumablePackageUploadService(
                1024, 3, Duration.ofSeconds(30), 2, 1024, clock);
        ResumablePackageUploadService.UploadProgress created = service.create("demo.zip", 3);

        clock.advance(Duration.ofSeconds(20));
        service.progress(created.uploadId());
        clock.advance(Duration.ofSeconds(20));
        assertThat(service.cleanupExpiredSessions()).isZero();
        clock.advance(Duration.ofSeconds(11));
        assertThat(service.cleanupExpiredSessions()).isEqualTo(1);
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
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
