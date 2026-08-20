package com.huawei.skillcenter.governance;

import java.time.Instant;

public record FavoriteRecord(String userId, String skillId, Instant createdAt) {
}
