package com.huawei.skillcenter.governance;

public record Actor(String userId, String role) {
    public Actor {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
    }
}
