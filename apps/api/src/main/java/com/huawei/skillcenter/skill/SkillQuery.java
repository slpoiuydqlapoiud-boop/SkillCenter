package com.huawei.skillcenter.skill;

public record SkillQuery(String query, String category, String status, String risk, int page, int pageSize, String sort) {
    public SkillQuery(String query, String category, String status, String risk, int page, int pageSize) {
        this(query, category, status, risk, page, pageSize, "updated");
    }

    public SkillQuery {
        page = Math.max(1, page);
        pageSize = Math.min(50, Math.max(1, pageSize));
        sort = SkillSort.normalize(sort);
    }
}
