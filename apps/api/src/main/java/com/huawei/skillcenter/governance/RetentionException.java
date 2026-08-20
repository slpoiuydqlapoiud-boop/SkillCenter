package com.huawei.skillcenter.governance;

public class RetentionException extends RuntimeException {
    private final String code;

    public RetentionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
