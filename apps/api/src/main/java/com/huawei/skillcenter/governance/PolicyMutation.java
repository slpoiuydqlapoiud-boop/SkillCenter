package com.huawei.skillcenter.governance;

import java.util.List;

public record PolicyMutation(List<Integer> pageSizeOptions, int maxPageSize, String minimumClientVersion,
                             String defaultCollectionVisibility) {
    public PolicyMutation {
        pageSizeOptions = List.copyOf(pageSizeOptions == null ? List.of() : pageSizeOptions);
    }
}
