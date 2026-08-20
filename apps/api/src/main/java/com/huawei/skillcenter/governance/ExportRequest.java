package com.huawei.skillcenter.governance;

public record ExportRequest(ExportDataset dataset, ExportFormat format, ExportFilters filters) {
    public ExportRequest {
        if (dataset == null) {
            throw new IllegalArgumentException("dataset is required");
        }
        if (format == null) {
            throw new IllegalArgumentException("format is required");
        }
        filters = filters == null ? ExportFilters.empty() : filters;
    }
}
