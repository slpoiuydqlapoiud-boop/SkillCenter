package com.huawei.skillcenter.release;

import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.SkillVersion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class ReleaseAdmissionService {
    private final GovernanceStore governanceStore;
    private final ReleaseRecordRepository releaseStore;
    private final ReleaseAdmissionMode mode;

    @Autowired
    public ReleaseAdmissionService(GovernanceStore governanceStore, ReleaseRecordRepository releaseStore,
                                    @Value("${skill-center.release-admission-mode:LEGACY_COMPATIBLE}") String mode) {
        this(governanceStore, releaseStore, ReleaseAdmissionMode.from(mode));
    }

    public ReleaseAdmissionService(GovernanceStore governanceStore, ReleaseRecordRepository releaseStore,
                                   ReleaseAdmissionMode mode) {
        this.governanceStore = require(governanceStore, "governanceStore");
        this.releaseStore = require(releaseStore, "releaseStore");
        this.mode = require(mode, "mode");
    }

    public ReleaseAdmissionDecision evaluate(String skillId, String version) {
        SkillVersion candidate = findDistributableVersion(skillId, version);
        List<ReleaseRecord> production = releaseStore.findAll(candidate.skillId(), candidate.version(),
                ReleaseEnvironment.PRODUCTION, null);
        if (production.isEmpty()) {
            return mode == ReleaseAdmissionMode.LEGACY_COMPATIBLE
                    ? ReleaseAdmissionDecision.legacy(mode)
                    : ReleaseAdmissionDecision.denied(mode, "RELEASE_ADMISSION_REQUIRED");
        }

        List<ReleaseRecord> promoted = production.stream()
                .filter(record -> record.status() == ReleaseStatus.PROMOTED)
                .toList();
        ReleaseRecord matching = promoted.stream()
                .filter(record -> candidate.sha256().equals(record.sha256()))
                .findFirst()
                .orElse(null);
        if (matching != null) {
            return ReleaseAdmissionDecision.promoted(mode, matching);
        }
        if (!promoted.isEmpty()) {
            return ReleaseAdmissionDecision.denied(mode, "RELEASE_ARTIFACT_MISMATCH");
        }
        return ReleaseAdmissionDecision.denied(mode, "RELEASE_NOT_PROMOTED");
    }

    public ReleaseAdmissionDecision requireDownloadable(String skillId, String version) {
        ReleaseAdmissionDecision decision = evaluate(skillId, version);
        if (!decision.allowed()) {
            String message = switch (decision.reasonCode()) {
                case "RELEASE_ADMISSION_REQUIRED" -> "Release admission is required";
                case "RELEASE_NOT_PROMOTED" -> "Release is not promoted";
                case "RELEASE_ARTIFACT_MISMATCH" -> "Release artifact does not match the governed version";
                default -> "Release is not eligible for distribution";
            };
            throw new ReleaseAdmissionException(decision.reasonCode(), message);
        }
        return decision;
    }

    public ReleaseAdmissionMode mode() {
        return mode;
    }

    private SkillVersion findDistributableVersion(String skillId, String version) {
        if (skillId == null || skillId.isBlank() || version == null || version.isBlank()) {
            throw new ReleaseAdmissionException("RELEASE_VERSION_NOT_DISTRIBUTABLE", "Skill version is not distributable");
        }
        return governanceStore.snapshot().versions().stream()
                .filter(candidate -> skillId.equals(candidate.skillId()) && version.equals(candidate.version()))
                .filter(candidate -> "published".equals(candidate.status()) || "deprecated".equals(candidate.status()))
                .max(Comparator.comparing(SkillVersion::publishedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow(() -> new ReleaseAdmissionException("RELEASE_VERSION_NOT_DISTRIBUTABLE",
                        "Skill version is not distributable"));
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
