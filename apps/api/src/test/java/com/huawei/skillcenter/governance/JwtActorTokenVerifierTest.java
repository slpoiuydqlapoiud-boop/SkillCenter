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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtActorTokenVerifierTest {
    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    void verifiesRs256SubjectRoleIssuerAudienceAndExpiry() throws Exception {
        String token = token("{\"sub\":\"alice\",\"roles\":[\"viewer\"],"
                + "\"iss\":\"https://sso.example\",\"aud\":\"skill-center\",\"exp\":1798287000}");

        Actor actor = verifier().verify(token);

        assertEquals(new Actor("alice", "viewer"), actor);
    }

    @Test
    void rejectsInvalidSignatureWithoutExposingToken() throws Exception {
        String token = token("{\"sub\":\"alice\",\"role\":\"admin\",\"exp\":1798287000}");
        String invalid = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        ForbiddenException exception = assertThrows(ForbiddenException.class,
                () -> verifier().verify(invalid));

        assertEquals("Invalid bearer token", exception.getMessage());
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String token = token("{\"sub\":\"alice\",\"role\":\"admin\",\"exp\":1766664000}");

        ForbiddenException exception = assertThrows(ForbiddenException.class,
                () -> verifier().verify(token));

        assertEquals("Invalid bearer token", exception.getMessage());
    }

    @Test
    void rejectsAlgorithmConfusionAndMissingRole() throws Exception {
        String headerWithHs256 = encoded("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = encoded("{\"sub\":\"alice\",\"exp\":1798287000}");
        String token = headerWithHs256 + "." + payload + ".signature";

        ForbiddenException exception = assertThrows(ForbiddenException.class,
                () -> verifier().verify(token));

        assertEquals("Invalid bearer token", exception.getMessage());
    }

    @Test
    void rejectsWrongIssuerOrAudience() throws Exception {
        String token = token("{\"sub\":\"alice\",\"role\":\"admin\","
                + "\"iss\":\"https://other.example\",\"aud\":\"other\",\"exp\":1798287000}");

        ForbiddenException exception = assertThrows(ForbiddenException.class,
                () -> verifier().verify(token));

        assertEquals("Invalid bearer token", exception.getMessage());
    }

    private JwtActorTokenVerifier verifier() {
        return new JwtActorTokenVerifier(keyPair.getPublic(), "https://sso.example", "skill-center", 30,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String token(String payload) throws Exception {
        String header = encoded("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String encodedPayload = encoded(payload);
        String signingInput = header + "." + encodedPayload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
