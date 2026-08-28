package com.huawei.skillcenter.operations;

import com.huawei.skillcenter.distribution.ArtifactStorage;
import com.huawei.skillcenter.distribution.ArtifactStorageHealth;
import com.huawei.skillcenter.distribution.ArtifactStorageReadiness;
import com.huawei.skillcenter.distribution.ArtifactStorageProbeResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Aggregates the selected artifact adapter into an admin-safe readiness signal. */
@Service
public class ArtifactStorageReadinessService {
    private final ArtifactStorage storage;
    private final ArtifactStorageConnectivityProbeService probes;

    @Autowired
    public ArtifactStorageReadinessService(ArtifactStorage storage,
                                           ArtifactStorageConnectivityProbeService probes) {
        this(storage, probes, true);
    }

    public ArtifactStorageReadinessService(ArtifactStorage storage) {
        this(storage, null, false);
    }

    private ArtifactStorageReadinessService(ArtifactStorage storage,
                                            ArtifactStorageConnectivityProbeService probes,
                                            boolean ignored) {
        this.storage = storage;
        this.probes = probes;
    }

    public ArtifactStorageReadiness readiness() {
        if (storage instanceof ArtifactStorageHealth health) {
            ArtifactStorageReadiness readiness = health.readiness();
            ArtifactStorageReadiness base = readiness == null
                    ? unavailable("ARTIFACT_STORAGE_HEALTH_UNAVAILABLE", "制品存储就绪状态不可用")
                    : readiness;
            return applyProbe(base);
        }
        return applyProbe(unavailable("ARTIFACT_STORAGE_HEALTH_UNAVAILABLE", "制品存储适配器未提供就绪状态"));
    }

    private ArtifactStorageReadiness applyProbe(ArtifactStorageReadiness base) {
        ArtifactStorageProbeResult probe = probes == null ? null : probes.lastProbe();
        if (probe == null || "SKIPPED".equals(probe.status())) return base;
        if ("REACHABLE".equals(probe.status())) {
            return new ArtifactStorageReadiness(base.backend(), "READY", probe.reasonCode(),
                    "制品存储连通性探测通过");
        }
        return new ArtifactStorageReadiness(base.backend(), "NOT_READY", probe.reasonCode(),
                "制品存储连通性探测未通过");
    }

    private ArtifactStorageReadiness unavailable(String code, String summary) {
        return new ArtifactStorageReadiness("unknown", "NOT_READY", code, summary);
    }
}
