package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtOrganizationClaimsVerifierTest {
    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    void mapsTextArrayTeamClaimToAnAuthoritativeActor() throws Exception {
        Actor actor = verifier(false).verify(token("{\"sub\":\"alice\",\"role\":\"admin\","
                + "\"teams\":[\"team-b\",\"team-a\"],\"exp\":1798287000}"));

        assertEquals(new Actor("alice", "admin", Set.of("team-a", "team-b"), true), actor);
    }

    @Test
    void mapsSingleTextTeamAndMarksMissingOptionalClaimAuthoritative() throws Exception {
        Actor single = verifier(false).verify(token("{\"sub\":\"alice\",\"role\":\"admin\","
                + "\"teams\":\"team-a\",\"exp\":1798287000}"));
        Actor missing = verifier(false).verify(token("{\"sub\":\"alice\",\"role\":\"admin\","
                + "\"exp\":1798287000}"));

        assertEquals(Set.of("team-a"), single.teamIds());
        assertEquals(Set.of(), missing.teamIds());
        assertEquals(true, missing.teamClaimsAuthoritative());
    }

    @Test
    void requiredOrMalformedTeamClaimRejectsWithoutExposingClaimDetails() throws Exception {
        assertInvalid(verifier(true), token("{\"sub\":\"alice\",\"role\":\"admin\","
                + "\"exp\":1798287000}"));
        assertInvalid(verifier(false), token("{\"sub\":\"alice\",\"role\":\"admin\","
                + "\"teams\":{\"id\":\"team-a\"},\"exp\":1798287000}"));
        assertInvalid(verifier(false), token("{\"sub\":\"alice\",\"role\":\"admin\","
                + "\"teams\":[\"team-a\",\"team-a\"],\"exp\":1798287000}"));
    }

    @Test
    void rejectsMoreThanOneHundredTeamClaims() throws Exception {
        StringBuilder teams = new StringBuilder("[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) teams.append(',');
            teams.append("\"team-").append(i).append("\"");
        }
        teams.append(']');
        assertInvalid(verifier(false), token("{\"sub\":\"alice\",\"role\":\"admin\",\"teams\":"
                + teams + ",\"exp\":1798287000}"));
    }

    private JwtActorTokenVerifier verifier(boolean required) {
        return new JwtActorTokenVerifier(keyPair.getPublic(), "", "", 30,
                Clock.fixed(NOW, ZoneOffset.UTC), "teams", required);
    }

    private void assertInvalid(JwtActorTokenVerifier verifier, String token) {
        ForbiddenException exception = assertThrows(ForbiddenException.class, () -> verifier.verify(token));
        assertEquals("Invalid bearer token", exception.getMessage());
    }

    private String token(String payload) throws Exception {
        String header = encoded("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String body = encoded(payload);
        String input = header + "." + body;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
