package com.huawei.skillcenter.governance;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActorResolverAuthenticationTest {
    @Test
    void jwtModeUsesVerifiedTokenAndIgnoresSpoofedRoleHeaders() {
        ActorResolver resolver = new ActorResolver(ActorResolver.Mode.JWT, token -> new Actor("alice", "viewer"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer signed-token");
        when(request.getHeader(ActorResolver.USER_ID_HEADER)).thenReturn("attacker");
        when(request.getHeader(ActorResolver.ROLE_HEADER)).thenReturn("admin");

        assertEquals(new Actor("alice", "viewer"), resolver.resolve(request));
    }

    @Test
    void jwtModeRequiresBearerTokenAndDoesNotFallBackToLocalHeaders() {
        ActorResolver resolver = new ActorResolver(ActorResolver.Mode.JWT, token -> new Actor("alice", "viewer"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader(ActorResolver.USER_ID_HEADER)).thenReturn("attacker");
        when(request.getHeader(ActorResolver.ROLE_HEADER)).thenReturn("admin");

        ForbiddenException exception = assertThrows(ForbiddenException.class, () -> resolver.resolve(request));

        assertEquals("Bearer token required", exception.getMessage());
    }
}
