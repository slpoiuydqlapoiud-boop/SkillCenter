package com.huawei.skillcenter.governance;

import java.util.List;

public class QualityGateBlockedException extends RuntimeException {
    private final String skillId;
    private final String version;
    private final List<String> reasons;

    public QualityGateBlockedException(String skillId, String version, List<String> reasons) {
        super("Quality gate blocked publishing for " + skillId + " " + version + ": "
                + String.join(", ", reasons == null ? List.of() : reasons));
        this.skillId = skillId;
        this.version = version;
        this.reasons = List.copyOf(reasons == null ? List.of() : reasons);
    }

    public String skillId() { return skillId; }

    public String version() { return version; }

    public List<String> reasons() { return reasons; }
}
