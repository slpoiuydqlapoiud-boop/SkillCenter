package com.huawei.skillcenter.packageupload;

import org.springframework.http.HttpStatus;

public class ResumablePackageUploadException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public ResumablePackageUploadException(String code, String message, HttpStatus status) {
        super(code + ": " + message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
