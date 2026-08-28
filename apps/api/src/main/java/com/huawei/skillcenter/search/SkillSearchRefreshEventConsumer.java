package com.huawei.skillcenter.search;

/** Consumer boundary used by local Spring events and shared event replay. */
public interface SkillSearchRefreshEventConsumer {
    void onRefresh(SkillSearchRefreshEvent event);
}
