package com.huawei.skillcenter.quality;

public class RunnerScenarioNotAllowedException extends RuntimeException {
    public RunnerScenarioNotAllowedException(String scenario) {
        super("Runner scenario is not allowed: " + scenario);
    }
}
