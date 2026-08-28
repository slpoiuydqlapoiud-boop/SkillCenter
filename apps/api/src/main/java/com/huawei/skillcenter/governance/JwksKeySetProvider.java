package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Bounded, fail-closed JWKS key provider. */
public final class JwksKeySetProvider {
    private static final int MIN_CONNECT_TIMEOUT_MS = 100;
    private static final int MAX_CONNECT_TIMEOUT_MS = 10_000;
    private static final int MIN_REQUEST_TIMEOUT_MS = 100;
    private static final int MAX_REQUEST_TIMEOUT_MS = 15_000;
    private static final long MIN_CACHE_TTL_SECONDS = 1;
    private static final long MAX_CACHE_TTL_SECONDS = 86_400;
    private static final int MIN_RESPONSE_BYTES = 4_096;
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_KEYS = 128;
    private static final int MAX_KID_LENGTH = 128;
    private static final Duration REFRESH_BACKOFF = Duration.ofSeconds(1);
    private static final Set<String> ROOT_FIELDS = Set.of("keys");
    private static final Set<String> KEY_FIELDS = Set.of("kty", "use", "alg", "kid", "n", "e");
    private final URI uri;
    private final Duration requestTimeout;
    private final Duration cacheTtl;
    private final int maxResponseBytes;
    private final Clock clock;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private final Object refreshLock = new Object();
    private volatile JwksKeySetSnapshot snapshot;
    private String lastMissingKid;
    private Instant lastMissingAt;
    private Instant lastFailedRefreshAt;

    public JwksKeySetProvider(URI uri, Duration connectTimeout, Duration requestTimeout,
                              Duration cacheTtl, int maxResponseBytes, Clock clock) {
        this.uri = validateUri(uri);
        validateDuration(connectTimeout, MIN_CONNECT_TIMEOUT_MS, MAX_CONNECT_TIMEOUT_MS, "connect timeout");
        this.requestTimeout = validateDuration(requestTimeout, MIN_REQUEST_TIMEOUT_MS, MAX_REQUEST_TIMEOUT_MS,
                "request timeout");
        this.cacheTtl = validateDuration(cacheTtl, MIN_CACHE_TTL_SECONDS * 1_000,
                MAX_CACHE_TTL_SECONDS * 1_000, "cache TTL");
        if (maxResponseBytes < MIN_RESPONSE_BYTES || maxResponseBytes > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("JWKS response size is out of bounds");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Optional<PublicKey> find(String kid) {
        if (kid == null || kid.isBlank() || kid.length() > MAX_KID_LENGTH) {
            return Optional.empty();
        }
        JwksKeySetSnapshot current = snapshot;
        if (usable(current)) {
            PublicKey key = current.keys().get(kid);
            if (key != null) return Optional.of(key);
        }
        synchronized (refreshLock) {
            Instant now = clock.instant();
            current = snapshot;
            if (usable(current)) {
                PublicKey key = current.keys().get(kid);
                if (key != null) return Optional.of(key);
                if (kid.equals(lastMissingKid) && lastMissingAt != null
                        && now.isBefore(lastMissingAt.plus(REFRESH_BACKOFF))) {
                    return Optional.empty();
                }
                lastMissingKid = kid;
                lastMissingAt = now;
            } else if (lastFailedRefreshAt != null
                    && now.isBefore(lastFailedRefreshAt.plus(REFRESH_BACKOFF))) {
                return Optional.empty();
            }
            JwksKeySetSnapshot refreshed = fetch();
            if (refreshed == null) {
                lastFailedRefreshAt = now;
                return Optional.empty();
            }
            lastFailedRefreshAt = null;
            snapshot = refreshed;
            if (refreshed.keys().containsKey(kid)) {
                lastMissingKid = null;
                lastMissingAt = null;
            }
            return Optional.ofNullable(refreshed.keys().get(kid));
        }
    }

    private boolean usable(JwksKeySetSnapshot value) {
        return value != null && clock.instant().isBefore(value.loadedAt().plus(cacheTtl));
    }

    private JwksKeySetSnapshot fetch() {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(requestTimeout)
                    .header("Accept", "application/jwk-set+json, application/json")
                    .GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declaredLength > maxResponseBytes) return null;
            try (InputStream body = response.body()) {
                byte[] bytes = readBounded(body);
                return parse(bytes);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private byte[] readBounded(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxResponseBytes, 16 * 1024));
        byte[] buffer = new byte[4096];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxResponseBytes) throw new IllegalArgumentException("JWKS response too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private JwksKeySetSnapshot parse(byte[] bytes) throws Exception {
        JsonNode root = mapper.readTree(bytes);
        if (root == null || !root.isObject() || !onlyFields(root, ROOT_FIELDS)) return null;
        JsonNode keys = root.get("keys");
        if (keys == null || !keys.isArray() || keys.isEmpty() || keys.size() > MAX_KEYS) return null;
        Map<String, PublicKey> parsed = new HashMap<>();
        for (JsonNode node : keys) {
            if (node == null || !node.isObject() || !onlyFields(node, KEY_FIELDS)) return null;
            String kid = text(node, "kid");
            if (kid.isBlank() || kid.length() > MAX_KID_LENGTH || parsed.containsKey(kid)) return null;
            if (!isOptionalText(node, "use") || !isOptionalText(node, "alg")
                    || !"RSA".equals(text(node, "kty")) || (!text(node, "use").isBlank() && !"sig".equals(text(node, "use")))
                    || (!text(node, "alg").isBlank() && !"RS256".equals(text(node, "alg")))) return null;
            byte[] modulus = decode(node.get("n"));
            byte[] exponent = decode(node.get("e"));
            if (modulus == null || exponent == null) return null;
            BigInteger n = new BigInteger(1, modulus);
            BigInteger e = new BigInteger(1, exponent);
            if (n.bitLength() < 2048 || e.compareTo(BigInteger.ONE) <= 0 || !e.testBit(0)) return null;
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
            parsed.put(kid, key);
        }
        return new JwksKeySetSnapshot(parsed, clock.instant());
    }

    private boolean onlyFields(JsonNode node, Set<String> allowed) {
        var fields = node.fieldNames();
        Set<String> seen = new HashSet<>();
        while (fields.hasNext()) seen.add(fields.next());
        return allowed.containsAll(seen);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private boolean isOptionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isTextual();
    }

    private byte[] decode(JsonNode value) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) return null;
        try {
            return Base64.getUrlDecoder().decode(value.asText());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static URI validateUri(URI value) {
        if (value == null || !value.isAbsolute() || value.getHost() == null
                || value.getUserInfo() != null || value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("JWKS URI is invalid");
        }
        String scheme = value.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"https".equals(scheme) && !("http".equals(scheme) && loopback(value.getHost()))) {
            throw new IllegalArgumentException("JWKS URI must use HTTPS");
        }
        return value;
    }

    private static boolean loopback(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        return "localhost".equals(normalized) || "127.0.0.1".equals(normalized) || "::1".equals(normalized);
    }

    private static Duration validateDuration(Duration value, long minMillis, long maxMillis, String name) {
        if (value == null || value.toMillis() < minMillis || value.toMillis() > maxMillis) {
            throw new IllegalArgumentException("JWKS " + name + " is out of bounds");
        }
        return value;
    }
}
