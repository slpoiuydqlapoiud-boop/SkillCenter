package com.huawei.skillcenter.personal;

import java.time.OffsetDateTime;

public record InvocationHistoryView(OffsetDateTime occurredAt, String skillId, String version,
                                    String clientType, String clientVersion, String status,
                                    long durationMs, String errorCode) {
}
