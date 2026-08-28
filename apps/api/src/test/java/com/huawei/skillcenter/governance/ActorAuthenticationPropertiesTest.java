package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ActorAuthenticationPropertiesTest {
    @Test
    void jwtModeRejectsMissingVerificationSource() {
        ActorAuthenticationProperties properties = new ActorAuthenticationProperties();
        properties.setMode("jwt");

        assertThrows(IllegalStateException.class, () -> new ActorResolver(properties));
    }

    @Test
    void jwtModeRejectsAmbiguousPemAndJwksSources() {
        ActorAuthenticationProperties properties = new ActorAuthenticationProperties();
        properties.setMode("jwt");
        properties.getJwt().setPublicKey("configured");
        properties.getJwt().setJwksUri("https://sso.example/.well-known/jwks.json");

        assertThrows(IllegalStateException.class, () -> new ActorResolver(properties));
    }

    @Test
    void jwtNetworkAndCachePropertiesRejectUnsafeBounds() {
        ActorAuthenticationProperties.JwtProperties jwt = new ActorAuthenticationProperties.JwtProperties();

        assertThrows(IllegalArgumentException.class, () -> jwt.setConnectTimeoutMs(99));
        assertThrows(IllegalArgumentException.class, () -> jwt.setRequestTimeoutMs(15_001));
        assertThrows(IllegalArgumentException.class, () -> jwt.setCacheTtlSeconds(86_401));
        assertThrows(IllegalArgumentException.class, () -> jwt.setMaxResponseBytes(4_095));
    }
}
