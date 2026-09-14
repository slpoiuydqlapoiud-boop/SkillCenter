package com.huawei.skillcenter.governance;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActorResolverLocalAuthenticationTest {
    @Test
    void strictLocalModeRejectsForgedHeadersAndAcceptsIssuedBearerToken() {
        ActorAuthenticationProperties properties = new ActorAuthenticationProperties();
        ActorAuthenticationProperties.LocalProperties local = new ActorAuthenticationProperties.LocalProperties();
        local.setUsername("admin");
        local.setPassword("secret");
        local.setRole("ADMIN");
        local.setRequireToken(true);
        properties.setLocal(local);
        LocalAuthenticationService service = new LocalAuthenticationService(properties,
                Clock.fixed(Instant.parse("2026-09-09T02:00:00Z"), ZoneOffset.UTC));
        ActorResolver resolver = new ActorResolver(ActorResolver.Mode.LOCAL, token -> {
            throw new AssertionError("JWT verifier must not be used");
        }, service, true);

        HttpServletRequest forged = mock(HttpServletRequest.class);
        when(forged.getHeader(ActorResolver.USER_ID_HEADER)).thenReturn("attacker");
        when(forged.getHeader(ActorResolver.ROLE_HEADER)).thenReturn("admin");
        assertThatThrownBy(() -> resolver.resolve(forged)).isInstanceOf(ForbiddenException.class);

        LocalAuthenticationService.Session session = service.authenticate("admin", "secret");
        HttpServletRequest authenticated = mock(HttpServletRequest.class);
        when(authenticated.getHeader(ActorResolver.AUTHORIZATION_HEADER)).thenReturn("Bearer " + session.token());
        assertThat(resolver.resolve(authenticated)).isEqualTo(new Actor("admin", "admin"));
    }
}
