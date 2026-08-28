package com.huawei.skillcenter.governance;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "skill-center.security.authentication.mode=jwt",
        "skill-center.security.authentication.jwt.issuer=https://sso.example",
        "skill-center.security.authentication.jwt.audience=skill-center"
})
@AutoConfigureMockMvc
class ActorResolverJwtConfigurationTest {
    private static KeyPair keyPair;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @DynamicPropertySource
    static void jwtKey(DynamicPropertyRegistry registry) {
        registry.add("skill-center.security.authentication.jwt.public-key", () -> pem(keyPair));
    }

    @Test
    void signedJwtSelectsActorAndIgnoresClientRoleHeader() throws Exception {
        mockMvc.perform(get("/api/v1/skills")
                        .header(ActorResolver.AUTHORIZATION_HEADER, "Bearer " + token("alice", "viewer"))
                        .header(ActorResolver.USER_ID_HEADER, "attacker")
                        .header(ActorResolver.ROLE_HEADER, "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void jwtModeRejectsMissingBearerEvenWhenAdminHeaderIsSpoofed() throws Exception {
        mockMvc.perform(get("/api/v1/skills")
                        .header(ActorResolver.USER_ID_HEADER, "attacker")
                        .header(ActorResolver.ROLE_HEADER, "admin"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private static String token(String subject, String role) throws Exception {
        String header = encoded("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = encoded("{\"sub\":\"" + subject + "\",\"roles\":[\"" + role
                + "\"],\"iss\":\"https://sso.example\",\"aud\":\"skill-center\",\"exp\":"
                + (Instant.now().getEpochSecond() + 300) + "}");
        String input = header + "." + payload;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }

    private static String pem(KeyPair pair) {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
