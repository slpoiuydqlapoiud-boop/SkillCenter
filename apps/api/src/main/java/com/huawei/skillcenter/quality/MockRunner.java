package com.huawei.skillcenter.quality;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

@Component
@ConditionalOnProperty(name = "skill-center.providers.runner", havingValue = "mock", matchIfMissing = true)
public class MockRunner implements SkillRunner {
    @Override
    public String providerId() {
        return "mock-runner";
    }

    @Override
    public String providerVersion() {
        return "1.0";
    }

    @Override
    public List<String> capabilities() {
        return List.of("execute", "timeout", "cancel");
    }

    @Override
    public RunnerExecutionResult execute(RunnerExecutionRequest request) {
        String scenario = request.scenario();
        RunnerExecutionStatus status = switch (scenario) {
            case "failure" -> RunnerExecutionStatus.FAILED;
            case "timeout" -> RunnerExecutionStatus.TIMED_OUT;
            case "cancel", "cancelled" -> RunnerExecutionStatus.CANCELLED;
            default -> RunnerExecutionStatus.SUCCEEDED;
        };
        String material = String.join("|", request.skillId(), request.skillVersion(), request.suiteId(), request.caseId(), scenario);
        long duration = 20 + Math.floorMod(material.hashCode(), 80);
        String error = status == RunnerExecutionStatus.SUCCEEDED ? "" : "MOCK_" + status.name();
        return new RunnerExecutionResult(status, providerId(), providerVersion(), "mock", duration,
                sha256(material), error);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
