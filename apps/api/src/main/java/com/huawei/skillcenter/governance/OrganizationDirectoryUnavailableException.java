package com.huawei.skillcenter.governance;

public class OrganizationDirectoryUnavailableException extends RuntimeException {
    private final String reasonCode;

    public OrganizationDirectoryUnavailableException(String reasonCode) {
        super("Organization directory unavailable");
        this.reasonCode = reasonCode == null || reasonCode.isBlank() ? "DIRECTORY_UNAVAILABLE" : reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
