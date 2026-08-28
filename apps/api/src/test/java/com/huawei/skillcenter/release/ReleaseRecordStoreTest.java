package com.huawei.skillcenter.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseRecordStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-24T01:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void persistsRecordsAndReloadsThemInUpdatedOrder() {
        Path statePath = tempDir.resolve("releases.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ReleaseRecordStore store = new ReleaseRecordStore(statePath, mapper);

        ReleaseRecord first = request("release-1", "skill-a", "1.0.0", "idem-1", NOW);
        ReleaseRecord second = request("release-2", "skill-a", "1.1.0", "idem-2", NOW.plusSeconds(10));
        store.create(first);
        store.create(second);

        ReleaseRecordStore reloaded = new ReleaseRecordStore(statePath, mapper);
        assertThat(reloaded.find("release-1")).contains(first);
        assertThat(reloaded.findAll("skill-a", null, null, null).stream()
                .map(ReleaseRecord::releaseId)).containsExactly("release-2", "release-1");
        assertThat(reloaded.findByIdempotencyKey("idem-2")).contains(second);
    }

    @Test
    void jsonStoreImplementsTheReleaseRecordRepositoryPort() {
        ReleaseRecordRepository repository = new ReleaseRecordStore(tempDir.resolve("releases.json"),
                new ObjectMapper().findAndRegisterModules());

        ReleaseRecord record = request("release-port", "skill-port", "1.0.0", "idem-port", NOW);
        repository.create(record);

        assertThat(repository.find("release-port")).contains(record);
    }

    @Test
    void rejectsDuplicateIdempotencyAndActiveBusinessKey() {
        ReleaseRecordStore store = new ReleaseRecordStore(tempDir.resolve("releases.json"),
                new ObjectMapper().findAndRegisterModules());
        store.create(request("release-1", "skill-a", "1.0.0", "idem-1", NOW));

        assertThatThrownBy(() -> store.create(request("release-2", "skill-a", "1.0.0", "idem-1", NOW.plusSeconds(1))))
                .isInstanceOf(ReleaseConflictException.class)
                .hasMessageContaining("idempotencyKey");
        assertThatThrownBy(() -> store.create(request("release-3", "skill-a", "1.0.0", "idem-3", NOW.plusSeconds(1))))
                .isInstanceOf(ReleaseConflictException.class)
                .hasMessageContaining("active release");
        assertThat(store.findActiveBusinessKey("skill-a", "1.0.0", ReleaseEnvironment.STAGING))
                .isPresent();
    }

    @Test
    void replaceKeepsSingleRecordAndSupportsTerminalRetry() {
        ReleaseRecordStore store = new ReleaseRecordStore(tempDir.resolve("releases.json"),
                new ObjectMapper().findAndRegisterModules());
        ReleaseRecord requested = request("release-1", "skill-a", "1.0.0", "idem-1", NOW);
        store.create(requested);
        ReleaseRecord failed = requested.approve("reviewer", NOW.plusSeconds(1))
                .promoting("mock/release-1", NOW.plusSeconds(2))
                .failed("TARGET_FAILED", "mock/release-1", NOW.plusSeconds(3));
        store.replace(failed);

        assertThat(store.findAll("skill-a", "1.0.0", ReleaseEnvironment.STAGING, null))
                .containsExactly(failed);
        assertThat(store.findActiveBusinessKey("skill-a", "1.0.0", ReleaseEnvironment.STAGING))
                .isEmpty();
    }

    private ReleaseRecord request(String releaseId, String skillId, String version, String idempotencyKey,
                                  Instant requestedAt) {
        return ReleaseRecord.request(releaseId, skillId, version,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ReleaseEnvironment.STAGING, ReleaseGateSnapshot.passed(requestedAt), idempotencyKey,
                "admin", requestedAt);
    }
}
