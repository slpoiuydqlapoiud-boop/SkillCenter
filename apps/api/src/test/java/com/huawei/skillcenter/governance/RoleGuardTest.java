package com.huawei.skillcenter.governance;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleGuardTest {
    private final ActorResolver resolver = new ActorResolver();

    @Test
    void missingHeadersUseLocalAdminActor() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-User-Id")).thenReturn(null);
        when(request.getHeader("X-User-Role")).thenReturn(null);

        assertEquals(new Actor("local-user", "admin"), resolver.resolve(request));
    }

    @Test
    void roleIsNormalizedAndUnknownRoleIsRejected() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-User-Id")).thenReturn("alice");
        when(request.getHeader("X-User-Role")).thenReturn("Reviewer");
        assertEquals(new Actor("alice", "reviewer"), resolver.resolve(request));

        when(request.getHeader("X-User-Role")).thenReturn("owner");
        assertThrows(ForbiddenException.class, () -> resolver.resolve(request));
    }

    @Test
    void resolverAcceptsCanonicalDeveloperRole() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-User-Id")).thenReturn("developer-1");
        when(request.getHeader("X-User-Role")).thenReturn("developer");

        assertEquals(new Actor("developer-1", "developer"), resolver.resolve(request));
    }

    @Test
    void guardAllowsOnlyConfiguredRoles() {
        Actor reviewer = new Actor("alice", "reviewer");
        RoleGuard.require(reviewer, Set.of("reviewer", "admin"));
        assertThrows(ForbiddenException.class,
                () -> RoleGuard.require(new Actor("bob", "viewer"), Set.of("reviewer", "admin")));
    }

    @Test
    void canonicalDeveloperRoleUsesMaintainerPermissions() {
        RoleGuard.require(new Actor("developer-1", "developer"), Set.of("maintainer"));
    }
}
