package com.huawei.skillcenter.operations;

/** Optional operations probe for the optimization work-item queue. */
public interface OptimizationWorkItemStalenessProbe {
    OptimizationWorkItemStaleness health();
}
