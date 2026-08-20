package com.huawei.skillcenter.governance;

public class SkillVersionNotFoundException extends RuntimeException {
    public SkillVersionNotFoundException(String skillId, String version) {
        super("Skill version not found: " + skillId + ":" + version);
    }
}
