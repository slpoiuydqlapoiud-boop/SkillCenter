package com.huawei.skillcenter.persistence;

public final class JsonPersistenceBackend implements PersistenceBackend {
    @Override
    public String backendId() {
        return "json";
    }

    @Override
    public PersistenceBackendStatus status() {
        return PersistenceBackendStatus.ready(backendId(), null, null);
    }
}
