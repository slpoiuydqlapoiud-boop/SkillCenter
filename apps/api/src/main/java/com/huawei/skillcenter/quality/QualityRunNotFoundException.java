package com.huawei.skillcenter.quality;

public class QualityRunNotFoundException extends RuntimeException {
    public QualityRunNotFoundException(String id) {
        super("Evaluation run not found: " + id);
    }
}
