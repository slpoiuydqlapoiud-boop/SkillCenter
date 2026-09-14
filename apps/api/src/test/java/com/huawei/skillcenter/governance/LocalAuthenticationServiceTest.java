package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalAuthenticationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-09T02:00:00Z");

    @Test
    void authenticatesConfiguredPasswordAndResolvesShortLivedToken() {
        ActorAuthenticationProperties properties = properties("admin", "SkillCenter@2026", "ADMIN", 60);
        LocalAuthenticationService service = new LocalAuthenticationService(properties,
                Clock.fixed(NOW, ZoneOffset.UTC));

        LocalAuthenticationService.Session session = service.authenticate("admin", "SkillCenter@2026");

        assertThat(session.actor()).isEqualTo(new Actor("admin", "admin"));
        assertThat(session.token()).isNotBlank();
        assertThat(service.resolve(session.token())).contains(new Actor("admin", "admin"));
    }

    @Test
    void rejectsWrongPasswordAndExpiredToken() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        LocalAuthenticationService service = new LocalAuthenticationService(
                properties("member", "secret", "MEMBER", 60), clock);

        assertThatThrownBy(() -> service.authenticate("member", "wrong"))
                .isInstanceOf(ForbiddenException.class);
        LocalAuthenticationService.Session session = service.authenticate("member", "secret");
        LocalAuthenticationService expired = new LocalAuthenticationService(
                properties("member", "secret", "MEMBER", 60), Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC));
        assertThat(expired.resolve(session.token())).isEmpty();
    }

    private ActorAuthenticationProperties properties(String username, String password, String role, long ttl) {
        ActorAuthenticationProperties properties = new ActorAuthenticationProperties();
        ActorAuthenticationProperties.LocalProperties local = new ActorAuthenticationProperties.LocalProperties();
        local.setUsername(username);
        local.setPassword(password);
        local.setRole(role);
        local.setTokenTtlSeconds(ttl);
        properties.setLocal(local);
        return properties;
    }
}
