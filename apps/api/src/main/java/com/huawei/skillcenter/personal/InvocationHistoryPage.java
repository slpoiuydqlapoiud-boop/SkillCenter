package com.huawei.skillcenter.personal;

import java.util.List;

public record InvocationHistoryPage(List<InvocationHistoryView> items, int page, int pageSize, long total) {
    public InvocationHistoryPage {
        items = List.copyOf(items == null ? List.of() : items);
    }
}
