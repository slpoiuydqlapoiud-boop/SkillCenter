package com.huawei.skillcenter.quality;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@ConditionalOnProperty(name = "skill-center.providers.observability", havingValue = "mock", matchIfMissing = true)
public class MockObservabilityProvider implements ObservabilityProvider {
    private final CopyOnWriteArrayList<RunnerExecutionSummary> summaries = new CopyOnWriteArrayList<>();

    @Override
    public String providerId() {
        return "mock-observability";
    }

    @Override
    public String providerVersion() {
        return "1.0";
    }

    @Override
    public List<String> capabilities() {
        return List.of("summary", "metrics");
    }

    @Override
    public void record(RunnerExecutionSummary summary) {
        summaries.add(summary);
    }

    public List<RunnerExecutionSummary> summaries() {
        return List.copyOf(summaries);
    }
}
