package com.huawei.skillcenter.governance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwksActorTokenVerifierTest {
    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private HttpServer server;
    private KeyPair oldKey;
    private KeyPair newKey;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        oldKey = generator.generateKeyPair();
        newKey = generator.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void verifiesExistingAndRotatedKidsWithOneRefreshForTheNewKid() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/jwks", exchange -> {
            if (requests.incrementAndGet() == 1) respond(exchange, jwks("old", oldKey));
            else respond(exchange, jwks("old", oldKey, "new", newKey));
        });
        server.start();
        JwksActorTokenVerifier verifier = verifier();

        assertEquals(new Actor("alice", "admin"), verifier.verify(token("old", oldKey,
                "{\"sub\":\"alice\",\"roles\":[\"admin\"],\"exp\":1798287000}")));
        assertEquals(new Actor("alice", "admin"), verifier.verify(token("new", newKey,
                "{\"sub\":\"alice\",\"roles\":[\"admin\"],\"exp\":1798287000}")));
        assertEquals(2, requests.get());
    }

    @Test
    void rejectsMissingKidAlgorithmConfusionAndExpiredClaimsWithoutUpstreamDetails() throws Exception {
        server.createContext("/jwks", exchange -> respond(exchange, jwks("old", oldKey)));
        server.start();
        JwksActorTokenVerifier verifier = verifier();

        assertInvalid(verifier, tokenWithoutKid(oldKey));
        assertInvalid(verifier, encoded("{\"alg\":\"HS256\",\"kid\":\"old\"}"),
                encoded("{\"sub\":\"alice\",\"role\":\"admin\",\"exp\":1798287000}"), "signature");
        assertInvalid(verifier, token("old", oldKey,
                "{\"sub\":\"alice\",\"role\":\"admin\",\"exp\":1766664000}"));
    }

    private JwksActorTokenVerifier verifier() {
        return new JwksActorTokenVerifier(new JwksKeySetProvider(URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks"),
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMinutes(5), 262_144,
                Clock.fixed(NOW, ZoneOffset.UTC)), "", "", 30,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String token(String kid, KeyPair key, String claims) throws Exception {
        String header = encoded("{\"alg\":\"RS256\",\"kid\":\"" + kid + "\",\"typ\":\"JWT\"}");
        return signed(header, encoded(claims), key);
    }

    private String tokenWithoutKid(KeyPair key) throws Exception {
        String header = encoded("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        return signed(header, encoded("{\"sub\":\"alice\",\"role\":\"admin\",\"exp\":1798287000}"), key);
    }

    private String signed(String header, String payload, KeyPair key) throws Exception {
        String input = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private void assertInvalid(JwksActorTokenVerifier verifier, String token) {
        assertInvalid(verifier, token, null, null);
    }

    private void assertInvalid(JwksActorTokenVerifier verifier, String header, String payload, String signature) {
        String token = payload == null ? header : header + "." + payload + "." + signature;
        ForbiddenException exception = assertThrows(ForbiddenException.class, () -> verifier.verify(token));
        assertEquals("Invalid bearer token", exception.getMessage());
    }

    private String jwks(Object... values) {
        StringBuilder result = new StringBuilder("{\"keys\":[");
        for (int i = 0; i < values.length; i += 2) {
            if (i > 0) result.append(',');
            RSAPublicKey publicKey = (RSAPublicKey) ((KeyPair) values[i + 1]).getPublic();
            result.append("{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"")
                    .append(values[i]).append("\",\"n\":\"")
                    .append(Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.getModulus().toByteArray()))
                    .append("\",\"e\":\"AQAB\"}");
        }
        return result.append("]}").toString();
    }

    private String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
