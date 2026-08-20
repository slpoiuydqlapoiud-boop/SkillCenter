package com.huawei.skillcenter.operations;

import org.springframework.http.HttpStatus;

public class MetricsTokenException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public MetricsTokenException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
