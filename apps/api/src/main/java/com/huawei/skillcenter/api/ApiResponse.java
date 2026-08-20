package com.huawei.skillcenter.api;

public record ApiResponse<T>(T data, String requestId) {
}
