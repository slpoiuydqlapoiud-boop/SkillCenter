package com.huawei.skillcenter.quality;

public class CompatibilityMatrixNotFoundException extends RuntimeException {
    public CompatibilityMatrixNotFoundException(String matrixRunId) {
        super("Compatibility matrix not found: " + matrixRunId);
    }
}
