package com.huawei.skillcenter.persistence;

public class PersistenceControlException extends RuntimeException {
    private final String code;

    public PersistenceControlException(String code) {
        super(code == null ? "" : code.trim());
        this.code = code == null ? "" : code.trim();
    }

    public PersistenceControlException(String code, Throwable cause) {
        super(code == null ? "" : code.trim(), cause);
        this.code = code == null ? "" : code.trim();
    }

    public String getCode() {
        return code;
    }
}
