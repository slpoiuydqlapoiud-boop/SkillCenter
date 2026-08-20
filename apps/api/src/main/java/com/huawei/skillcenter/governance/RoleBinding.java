package com.huawei.skillcenter.governance;

import java.time.Instant;

public record RoleBinding(String userId, String role, String teamId, String status,
                          String changedBy, Instant changedAt) {
}
