package com.huawei.skillcenter.security;

public class IdempotencyException extends RuntimeException {
    private final String code;

    public IdempotencyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
