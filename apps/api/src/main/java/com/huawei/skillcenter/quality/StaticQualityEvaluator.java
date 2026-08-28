package com.huawei.skillcenter.quality;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class StaticQualityEvaluator {
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?$");

    public StaticQualityReport evaluate(String skillId, String skillVersion) {
        boolean idPresent = skillId != null && !skillId.isBlank();
        boolean versionValid = skillVersion != null && SEMVER.matcher(skillVersion).matches();
        List<StaticQualityCheck> checks = List.of(
                new StaticQualityCheck("SKILL_ID_PRESENT", idPresent,
                        idPresent ? "Skill ID 已提供" : "Skill ID 缺失"),
                new StaticQualityCheck("VERSION_SEMVER", versionValid,
                        versionValid ? "版本符合 SemVer" : "版本必须符合 SemVer"));
        int passed = (int) checks.stream().filter(StaticQualityCheck::passed).count();
        return new StaticQualityReport(checks.isEmpty() ? 0 : passed * 100 / checks.size(), checks.size(), passed, checks);
    }
}
