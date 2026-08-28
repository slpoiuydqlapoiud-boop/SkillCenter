package com.huawei.skillcenter.packageupload;

/** Safe, non-secret summary of a local package security finding. */
public record PackageSecurityFinding(String code, String path, String severity, String reason) {
}
