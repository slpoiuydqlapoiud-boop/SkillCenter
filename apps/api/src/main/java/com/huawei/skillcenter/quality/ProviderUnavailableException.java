package com.huawei.skillcenter.quality;

public class ProviderUnavailableException extends RuntimeException {
    private final String providerId;
    private final String code;

    public ProviderUnavailableException(String providerId, String code) {
        super(code + ": " + providerId);
        this.providerId = providerId;
        this.code = code;
    }

    public String providerId() {
        return providerId;
    }

    public String code() {
        return code;
    }
}
