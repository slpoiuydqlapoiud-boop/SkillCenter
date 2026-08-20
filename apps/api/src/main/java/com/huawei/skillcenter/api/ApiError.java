package com.huawei.skillcenter.api;

import java.util.List;

public record ApiError(String code, String message, List<ErrorDetail> details) {
    public record ErrorDetail(String path, String reason) {
    }
}
