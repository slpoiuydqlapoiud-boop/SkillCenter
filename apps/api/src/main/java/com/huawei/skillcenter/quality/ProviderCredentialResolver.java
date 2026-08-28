package com.huawei.skillcenter.quality;

/** Resolves a non-secret reference immediately before an outbound provider call. */
public interface ProviderCredentialResolver {
    String resolve(String credentialRef);
}
