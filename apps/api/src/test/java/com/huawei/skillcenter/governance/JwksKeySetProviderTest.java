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
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JwksKeySetProviderTest {
    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private HttpServer server;
    private KeyPair first;
    private KeyPair second;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        first = generator.generateKeyPair();
        second = generator.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void readsRsaKeysAndRefreshesForUnknownKidOnce() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/jwks", exchange -> {
            if (requests.incrementAndGet() == 1) respond(exchange, jwks("old", first));
            else respond(exchange, jwks("old", first, "new", second));
        });
        server.start();
        JwksKeySetProvider provider = provider(Duration.ofMinutes(5), 262_144);

        assertTrue(provider.find("old").isPresent());
        assertTrue(provider.find("new").isPresent());
        assertEquals(2, requests.get());
    }

    @Test
    void expiredCacheDoesNotRemainTrustedWhenRefreshFails() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/jwks", exchange -> {
            requests.incrementAndGet();
            if (requests.get() == 1) respond(exchange, jwks("old", first));
            else respond(exchange, "not-json");
        });
        server.start();
        MutableClock clock = new MutableClock(NOW);
        JwksKeySetProvider provider = new JwksKeySetProvider(uri(), Duration.ofSeconds(1),
                Duration.ofSeconds(2), Duration.ofSeconds(5), 262_144, clock);

        assertTrue(provider.find("old").isPresent());
        clock.advance(Duration.ofSeconds(6));
        assertFalse(provider.find("old").isPresent());
        assertEquals(2, requests.get());
    }

    @Test
    void rejectsOversizedAndInvalidJwksResponses() {
        server.createContext("/jwks", exchange -> respond(exchange, "x".repeat(5_000)));
        server.start();
        JwksKeySetProvider provider = provider(Duration.ofMinutes(5), 4_096);

        assertFalse(provider.find("a").isPresent());
    }

    @Test
    void doesNotRefreshRepeatedlyForTheSameUnknownKidWithinBackoff() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/jwks", exchange -> {
            requests.incrementAndGet();
            respond(exchange, jwks("old", first));
        });
        server.start();
        JwksKeySetProvider provider = provider(Duration.ofMinutes(5), 262_144);

        assertTrue(provider.find("old").isPresent());
        assertFalse(provider.find("ghost").isPresent());
        assertFalse(provider.find("ghost").isPresent());
        assertEquals(2, requests.get());
    }

    private JwksKeySetProvider provider(Duration ttl, int maxBytes) {
        return new JwksKeySetProvider(uri(), Duration.ofSeconds(1), Duration.ofSeconds(2), ttl,
                maxBytes, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
    }

    private String jwks(Object... values) {
        StringBuilder result = new StringBuilder("{\"keys\":[");
        for (int i = 0; i < values.length; i += 2) {
            if (i > 0) result.append(',');
            result.append("{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"")
                    .append(values[i]).append("\",\"n\":\"")
                    .append(base64(((RSAPublicKey) ((KeyPair) values[i + 1]).getPublic()).getModulus().toByteArray()))
                    .append("\",\"e\":\"AQAB\"}");
        }
        return result.append("]}").toString();
    }

    private String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
