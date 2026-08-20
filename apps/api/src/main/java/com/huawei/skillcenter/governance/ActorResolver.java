package com.huawei.skillcenter.governance;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class ActorResolver {
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String ROLE_HEADER = "X-User-Role";
    private static final Set<String> ALLOWED_ROLES = Set.of("developer", "admin", "viewer", "maintainer", "reviewer");

    public Actor resolve(HttpServletRequest request) {
        String userId = headerOrDefault(request.getHeader(USER_ID_HEADER), "local-user");
        String role = headerOrDefault(request.getHeader(ROLE_HEADER), "admin").toLowerCase(Locale.ROOT);
        if (!ALLOWED_ROLES.contains(role)) {
            throw new ForbiddenException("Unknown actor role");
        }
        return new Actor(userId, role);
    }

    private String headerOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
