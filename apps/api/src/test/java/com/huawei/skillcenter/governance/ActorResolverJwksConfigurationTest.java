package com.huawei.skillcenter.governance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActorResolverJwksConfigurationTest {
    private HttpServer server;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", this::respondJwks);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void jwtResolverUsesConfiguredJwksAndDoesNotNeedStaticPem() throws Exception {
        ActorAuthenticationProperties properties = new ActorAuthenticationProperties();
        properties.setMode("jwt");
        properties.getJwt().setJwksUri("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks");
        ActorResolver resolver = new ActorResolver(properties);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ActorResolver.AUTHORIZATION_HEADER, "Bearer " + token());

        assertEquals(new Actor("alice", "admin", Set.of(), true), resolver.resolve(request));
    }

    private String token() throws Exception {
        String header = encoded("{\"alg\":\"RS256\",\"kid\":\"active\",\"typ\":\"JWT\"}");
        String payload = encoded("{\"sub\":\"alice\",\"roles\":[\"admin\"],\"exp\":"
                + (Instant.now().getEpochSecond() + 300) + "}");
        String input = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private void respondJwks(HttpExchange exchange) throws IOException {
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        String body = "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\","
                + "\"kid\":\"active\",\"n\":\""
                + Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.getModulus().toByteArray())
                + "\",\"e\":\"AQAB\"}]}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
