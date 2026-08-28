package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OptimizationSuggestionThresholdsStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsConfiguredThresholdsAndReloadsThem() {
        Path state = tempDir.resolve("suggestion-thresholds.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        OptimizationSuggestionThresholdsStore first = new OptimizationSuggestionThresholdsStore(state, mapper);
        OptimizationSuggestionThresholds configured = new OptimizationSuggestionThresholds(97.5, 800, 10);

        first.update(configured);

        assertThat(new OptimizationSuggestionThresholdsStore(state, mapper).get()).isEqualTo(configured);
    }
}
