package com.huawei.skillcenter.distribution;

import java.util.Optional;

/** Raw immutable-object operations shared by artifact and resumable-upload adapters. */
public interface S3ObjectClient {
    boolean readyForUse();

    PutResult putIfAbsent(String objectKey, byte[] content);

    Optional<byte[]> get(String objectKey);

    void delete(String objectKey);

    record PutResult(boolean stored, int statusCode) {
    }
}
