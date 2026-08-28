package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Verifies the narrow RS256 JWT contract used at the API identity boundary. */
public final class JwtActorTokenVerifier implements ActorTokenVerifier {
    private static final Set<String> ALLOWED_ROLES = Set.of("developer", "admin", "viewer", "maintainer", "reviewer");
    private static final int MAX_TOKEN_LENGTH = 16 * 1024;
    private final PublicKey publicKey;
    private final String issuer;
    private final String audience;
    private final long clockSkewSeconds;
    private final Clock clock;
    private final String teamClaim;
    private final boolean teamClaimRequired;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public JwtActorTokenVerifier(PublicKey publicKey, String issuer, String audience,
                                 long clockSkewSeconds, Clock clock) {
        this(publicKey, issuer, audience, clockSkewSeconds, clock, "", false);
    }

    public JwtActorTokenVerifier(PublicKey publicKey, String issuer, String audience,
                                 long clockSkewSeconds, Clock clock, String teamClaim,
                                 boolean teamClaimRequired) {
        if (publicKey == null) {
            throw new IllegalArgumentException("JWT public key must be configured");
        }
        if (clockSkewSeconds < 0 || clockSkewSeconds > 300) {
            throw new IllegalArgumentException("JWT clock skew must be between 0 and 300 seconds");
        }
        this.publicKey = publicKey;
        if (!"RSA".equalsIgnoreCase(publicKey.getAlgorithm())) {
            throw new IllegalArgumentException("JWT public key must use RSA");
        }
        this.issuer = clean(issuer);
        this.audience = clean(audience);
        this.clockSkewSeconds = clockSkewSeconds;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.teamClaim = normalizeTeamClaim(teamClaim);
        this.teamClaimRequired = teamClaimRequired;
    }

    public JwtActorTokenVerifier(String publicKeyPem, String issuer, String audience,
                                 long clockSkewSeconds, Clock clock) {
        this(parsePublicKey(publicKeyPem), issuer, audience, clockSkewSeconds, clock);
    }

    public JwtActorTokenVerifier(String publicKeyPem, String issuer, String audience,
                                 long clockSkewSeconds, Clock clock, String teamClaim,
                                 boolean teamClaimRequired) {
        this(parsePublicKey(publicKeyPem), issuer, audience, clockSkewSeconds, clock,
                teamClaim, teamClaimRequired);
    }

    @Override
    public Actor verify(String token) {
        return verifyWithKey(token, publicKey, issuer, audience, clockSkewSeconds, clock,
                teamClaim, teamClaimRequired);
    }

    static Actor verifyWithKey(String token, PublicKey signingKey, String issuer, String audience,
                               long clockSkewSeconds, Clock clock) {
        return verifyWithKey(token, signingKey, issuer, audience, clockSkewSeconds, clock, "", false);
    }

    static Actor verifyWithKey(String token, PublicKey signingKey, String issuer, String audience,
                               long clockSkewSeconds, Clock clock, String teamClaim,
                               boolean teamClaimRequired) {
        try {
            if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
                throw invalidToken();
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw invalidToken();
            }
            JsonNode header = MAPPER.readTree(decode(parts[0]));
            JsonNode claims = MAPPER.readTree(decode(parts[1]));
            JsonNode algorithm = header == null ? null : header.get("alg");
            if (header == null || claims == null || algorithm == null || !algorithm.isTextual()
                    || !"RS256".equals(algorithm.asText())) {
                throw invalidToken();
            }
            byte[] signatureBytes = Base64.getUrlDecoder().decode(parts[2]);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(signingKey);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(signatureBytes)) {
                throw invalidToken();
            }
            validateClaims(claims, issuer, audience, clockSkewSeconds, clock);
            JsonNode subjectNode = claims.get("sub");
            String subject = subjectNode != null && subjectNode.isTextual() ? subjectNode.asText().trim() : "";
            String role = roleOf(claims.path("roles"));
            if (role.isBlank()) {
                role = roleOf(claims.path("role"));
            }
            if (subject.isBlank() || subject.length() > 128 || !ALLOWED_ROLES.contains(role)) {
                throw invalidToken();
            }
            TeamClaims teams = parseTeamClaims(claims, teamClaim, teamClaimRequired);
            return new Actor(subject, role, teams.teamIds(), teams.authoritative());
        } catch (ForbiddenException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidToken();
        }
    }

    static String keyId(String token) {
        try {
            if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) throw invalidToken();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw invalidToken();
            }
            JsonNode header = MAPPER.readTree(decode(parts[0]));
            JsonNode algorithm = header == null ? null : header.get("alg");
            JsonNode keyId = header == null ? null : header.get("kid");
            String kid = keyId != null && keyId.isTextual() ? keyId.asText().trim() : "";
            if (header == null || algorithm == null || !algorithm.isTextual()
                    || !"RS256".equals(algorithm.asText())
                    || kid.isBlank() || kid.length() > 128) throw invalidToken();
            return kid;
        } catch (ForbiddenException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidToken();
        }
    }

    private static void validateClaims(JsonNode claims, String issuer, String audience,
                                       long clockSkewSeconds, Clock clock) {
        long now = clock.instant().getEpochSecond();
        long skew = clockSkewSeconds;
        JsonNode expiration = claims.get("exp");
        if (expiration == null || !expiration.isNumber() || expiration.asLong() < now - skew) {
            throw invalidToken();
        }
        JsonNode notBefore = claims.get("nbf");
        if (notBefore != null && (!notBefore.isNumber() || notBefore.asLong() > now + skew)) {
            throw invalidToken();
        }
        if (!issuer.isBlank() && !issuer.equals(claims.path("iss").asText(""))) {
            throw invalidToken();
        }
        if (!audience.isBlank() && !hasAudience(claims.get("aud"), audience)) {
            throw invalidToken();
        }
    }

    private static boolean hasAudience(JsonNode value, String audience) {
        if (value == null) {
            return false;
        }
        if (value.isTextual()) {
            return audience.equals(value.asText());
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                if (item.isTextual() && audience.equals(item.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String roleOf(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "";
        }
        if (value.isTextual()) {
                return normalizeRole(value.asText());
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                String candidate = normalizeRole(item.asText(""));
                if (ALLOWED_ROLES.contains(candidate)) {
                    return candidate;
                }
            }
        }
        return "";
    }

    private static String normalizeRole(String value) {
        String normalized = clean(value).toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("role_") ? normalized.substring("role_".length()) : normalized;
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static PublicKey parsePublicKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("JWT public key must be configured");
        }
        try {
            String content = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] bytes = Base64.getDecoder().decode(content);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (Exception exception) {
            throw new IllegalArgumentException("JWT public key is invalid", exception);
        }
    }

    static ForbiddenException invalidToken() {
        return new ForbiddenException("Invalid bearer token");
    }

    private static TeamClaims parseTeamClaims(JsonNode claims, String claimName, boolean required) {
        String normalizedClaim = normalizeTeamClaim(claimName);
        if (normalizedClaim.isBlank()) return new TeamClaims(Set.of(), false);
        JsonNode value = claims.get(normalizedClaim);
        if (value == null || value.isNull()) {
            if (required) throw invalidToken();
            return new TeamClaims(Set.of(), true);
        }
        List<JsonNode> values;
        if (value.isTextual()) {
            values = List.of(value);
        } else if (value.isArray() && !value.isEmpty()) {
            values = new java.util.ArrayList<>();
            value.elements().forEachRemaining(values::add);
        } else {
            throw invalidToken();
        }
        if (values.size() > 100) throw invalidToken();
        Set<String> teamIds = new LinkedHashSet<>();
        for (JsonNode item : values) {
            if (item == null || !item.isTextual()) throw invalidToken();
            String teamId = item.asText().trim();
            if (!teamId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") || !teamIds.add(teamId)) {
                throw invalidToken();
            }
        }
        return new TeamClaims(Collections.unmodifiableSet(new java.util.TreeSet<>(teamIds)), true);
    }

    static String normalizeTeamClaim(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank() && !normalized.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("JWT team claim is invalid");
        }
        return normalized;
    }

    private record TeamClaims(Set<String> teamIds, boolean authoritative) {
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
