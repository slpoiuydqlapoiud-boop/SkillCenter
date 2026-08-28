package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimizationSuggestionDispositionStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsAndReloadsDispositionBySkillVersionAndSuggestion() {
        Path state = tempDir.resolve("optimization-dispositions.json");
        OptimizationSuggestionDispositionStore first = new OptimizationSuggestionDispositionStore(
                state, new ObjectMapper().findAndRegisterModules());
        OptimizationSuggestionDisposition value = new OptimizationSuggestionDisposition(
                "skill-a", "1.0.0", "runtime-data", "ACKNOWLEDGED", "准备补充生产样本",
                "admin", "admin", Instant.parse("2026-08-21T01:02:03Z"));

        first.upsert(value);

        OptimizationSuggestionDispositionStore reloaded = new OptimizationSuggestionDispositionStore(
                state, new ObjectMapper().findAndRegisterModules());
        assertThat(reloaded.find("skill-a", "1.0.0", "runtime-data")).contains(value);
        assertThat(reloaded.findAll("skill-a", "1.0.0")).containsExactly(value);
        assertThat(reloaded.findAll("skill-b", "1.0.0")).isEmpty();
    }

    @Test
    void normalizesEvidenceTypeAndRequiresAnEvidenceIdForLinkedDisposition() {
        OptimizationSuggestionDispositionRequest request =
                new OptimizationSuggestionDispositionRequest("resolved", "note", "quality_snapshot", "snapshot-1");

        assertThat(request.normalizedStatus()).isEqualTo("RESOLVED");
        assertThat(request.normalizedEvidenceType()).isEqualTo("QUALITY_SNAPSHOT");
        assertThat(request.normalizedEvidenceId()).isEqualTo("snapshot-1");
        assertThatThrownBy(() -> new OptimizationSuggestionDispositionRequest(
                "OPEN", "", "QUALITY_SNAPSHOT", "bad id").normalizedEvidenceId())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OptimizationSuggestionDispositionRequest(
                "OPEN", "", "UNKNOWN", "evidence-1").normalizedEvidenceType())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsLegacyDispositionConstructorCompatibleWithNoEvidence() {
        OptimizationSuggestionDisposition value = new OptimizationSuggestionDisposition(
                "skill-a", "1.0.0", "runtime-data", "OPEN", "", "admin", "admin", Instant.now());

        assertThat(value.evidenceType()).isEqualTo("NONE");
        assertThat(value.evidenceId()).isEmpty();
    }

    @Test
    void rejectsPersistedDuplicateDispositionKeys() throws Exception {
        Path state = tempDir.resolve("duplicate-dispositions.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        OptimizationSuggestionDisposition value = new OptimizationSuggestionDisposition(
                "skill-a", "1.0.0", "runtime-data", "OPEN", "", "admin", "admin", Instant.now());
        mapper.writeValue(state.toFile(), List.of(value, value));

        assertThatThrownBy(() -> new OptimizationSuggestionDispositionStore(state, mapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to read optimization suggestion dispositions");
    }
}
