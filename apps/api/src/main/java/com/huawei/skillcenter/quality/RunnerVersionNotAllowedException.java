package com.huawei.skillcenter.quality;

public class RunnerVersionNotAllowedException extends RuntimeException {
    public RunnerVersionNotAllowedException(String skillId, String version) {
        super("Skill version is not published for runner execution: " + skillId + " " + version);
    }
}
