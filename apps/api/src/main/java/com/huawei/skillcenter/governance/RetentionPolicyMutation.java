package com.huawei.skillcenter.governance;

public record RetentionPolicyMutation(long policyVersion, int auditRetentionDays,
                                      int invocationRetentionDays, int installationRetentionDays) {
}
