package com.huawei.skillcenter.operations;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceObservationTest {
    @Test
    void rejectsTraceMetadataThatCouldBreakRedactionContract() {
        OffsetDateTime now = OffsetDateTime.now();
        assertThatThrownBy(() -> new TraceObservation("trace-a", "span-a", "skill-a", "1.0.0",
                "prompt=secret", "failure", 10, "ERR", "production", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation");
        assertThatThrownBy(() -> new TraceObservation("trace-a", "span-a", "skill-a", "1.0.0",
                "skill.run", "failure", 10, "ERR", "external", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataSource");
        assertThatThrownBy(() -> new TraceObservation("trace-a", "span-a", "skill-a", "1.0.0",
                "skill.run", "failure", 10, "ERR", "production", now,
                "runtime-a", "mcp-a", "bad id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("environment");
    }
}
