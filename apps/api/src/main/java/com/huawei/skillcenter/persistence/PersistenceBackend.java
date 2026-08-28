package com.huawei.skillcenter.persistence;

public interface PersistenceBackend {
    String backendId();

    PersistenceBackendStatus status();
}
