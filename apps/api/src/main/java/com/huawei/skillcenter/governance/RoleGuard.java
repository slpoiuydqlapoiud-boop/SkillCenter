package com.huawei.skillcenter.governance;

import java.util.Locale;
import java.util.Set;

public final class RoleGuard {
    private RoleGuard() {
    }

    public static void require(Actor actor, Set<String> allowedRoles) {
        if (actor == null || allowedRoles == null || allowedRoles.stream()
                .map(role -> role.toLowerCase(Locale.ROOT))
                .noneMatch(role -> role.equals(actor.role()) || isDeveloperMaintainerAlias(actor, role))) {
            throw new ForbiddenException("Actor does not have permission for this operation");
        }
    }

    public static boolean isDeveloper(Actor actor) {
        return actor != null && ("developer".equals(actor.role()) || "maintainer".equals(actor.role()));
    }

    private static boolean isDeveloperMaintainerAlias(Actor actor, String allowedRole) {
        return "developer".equals(actor.role()) && "maintainer".equals(allowedRole);
    }
}
