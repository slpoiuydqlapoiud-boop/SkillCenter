package com.huawei.skillcenter.governance;

import java.time.OffsetDateTime;

public record DownloadUrlResponse(String url, String token, OffsetDateTime expiresAt) {
}
