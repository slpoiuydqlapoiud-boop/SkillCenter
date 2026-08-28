package com.huawei.skillcenter.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimizationWorkItemDomainTest {
    @Test
    void acceptsPinnedSuiteContextAndNormalizesIdentifiers() {
        OptimizationWorkItem item = workItem(" suite-a ", " suite-v1 ");

        assertThat(item.suiteId()).isEqualTo("suite-a");
        assertThat(item.suiteVersion()).isEqualTo("suite-v1");
    }

    @Test
    void rejectsPartialOrUnboundedSuiteContext() {
        assertThatThrownBy(() -> workItem("suite-a", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> workItem("bad value", "suite-v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyConstructorAndLegacyJsonRemainReadable() throws Exception {
        OptimizationWorkItem legacy = legacyWorkItem();

        assertThat(legacy.suiteId()).isEmpty();
        assertThat(legacy.suiteVersion()).isEmpty();

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(legacy)
                .replace(",\"suiteId\":\"\",\"suiteVersion\":\"\"", "");
        OptimizationWorkItem reloaded = mapper.readValue(json, OptimizationWorkItem.class);

        assertThat(reloaded.suiteId()).isEmpty();
        assertThat(reloaded.suiteVersion()).isEmpty();
    }

    private OptimizationWorkItem workItem(String suiteId, String suiteVersion) {
        return new OptimizationWorkItem("work-1", "skill-a", "1.0.0", "runtime-latency",
                "运行 P95 延迟偏高", "LATENCY", "MEDIUM", List.of("p95Ms=1200"), "降低延迟",
                "admin", "OPEN", "", "NONE", "", "", "production", "runtime-a", "mcp-a",
                "llm-a", suiteId, suiteVersion, "admin", Instant.parse("2026-08-24T01:02:03Z"),
                "admin", Instant.parse("2026-08-24T01:02:03Z"));
    }

    private OptimizationWorkItem legacyWorkItem() {
        return new OptimizationWorkItem("work-legacy", "skill-a", "1.0.0", "runtime-latency",
                "运行 P95 延迟偏高", "LATENCY", "MEDIUM", List.of("p95Ms=1200"), "降低延迟",
                "admin", "OPEN", "", "NONE", "", "", "production", "runtime-a", "mcp-a",
                "llm-a", "admin", Instant.parse("2026-08-24T01:02:03Z"), "admin",
                Instant.parse("2026-08-24T01:02:03Z"));
    }
}
