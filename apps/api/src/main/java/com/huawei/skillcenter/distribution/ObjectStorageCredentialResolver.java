package com.huawei.skillcenter.distribution;

public interface ObjectStorageCredentialResolver {
    ObjectStorageCredentials resolve(String accessKeyIdRef, String secretAccessKeyRef);
}
