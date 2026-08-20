package com.huawei.skillcenter.governance;

public record ExportDownload(byte[] content, String fileName, String contentType, String sha256) {
}
