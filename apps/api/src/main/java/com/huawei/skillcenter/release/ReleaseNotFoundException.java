package com.huawei.skillcenter.release;

public class ReleaseNotFoundException extends RuntimeException {
    public ReleaseNotFoundException(String releaseId) {
        super("Release not found: " + releaseId);
    }
}
