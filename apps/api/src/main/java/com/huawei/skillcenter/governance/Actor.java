package com.huawei.skillcenter.governance;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

public record Actor(String userId, String role, Set<String> teamIds, boolean teamClaimsAuthoritative) {
    public Actor(String userId, String role) {
        this(userId, role, Set.of(), false);
    }

    public Actor {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String teamId : teamIds == null ? Set.<String>of() : teamIds) {
            if (teamId == null || !teamId.trim().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException("teamId must be a bounded identifier");
            }
            normalized.add(teamId.trim());
        }
        if (normalized.size() > 100) {
            throw new IllegalArgumentException("teamIds must contain at most 100 identifiers");
        }
        teamIds = Collections.unmodifiableSet(normalized);
    }
}
