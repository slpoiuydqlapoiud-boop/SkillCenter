package com.huawei.skillcenter.governance;

public class ExportException extends RuntimeException {
    private final String code;

    public ExportException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
