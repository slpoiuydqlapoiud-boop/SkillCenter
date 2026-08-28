package com.huawei.skillcenter.governance;

@FunctionalInterface
public interface OrganizationDirectoryCredentialResolver {
    String resolve(String reference);
}
