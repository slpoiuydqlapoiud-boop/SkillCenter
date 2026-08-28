package com.huawei.skillcenter.distribution;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipInputStream;

/** S3-compatible HTTP adapter using AWS Signature V4 without a vendor SDK. */
public final class S3CompatibleArtifactStorage implements ArtifactStorage, S3ObjectClient, ArtifactStorageHealth,
        ArtifactStorageConnectivityProbe, ArtifactStorageIdentity {
    public static final String IDENTITY = "object-storage-http";
    private static final String SCHEME = "s3://";
    private static final String SERVICE = "s3";
    private static final String TERMINATOR = "aws4_request";
    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);
    private final ObjectStorageConfig config;
    private final ObjectStorageCredentialResolver credentials;
    private final HttpClient client;
    private final Clock clock;

    public S3CompatibleArtifactStorage(ObjectStorageConfig config,
                                       ObjectStorageCredentialResolver credentials,
                                       HttpClient client,
                                       Clock clock) {
        this.config = config;
        this.credentials = credentials;
        this.client = client == null ? HttpClient.newHttpClient() : client;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public StoredArtifact store(Path source, String packageId) throws IOException {
        if (source == null || !Files.isRegularFile(source) || Files.isSymbolicLink(source)) {
            throw new IOException("artifact source is invalid");
        }
        String digest = sha256(source);
        long size = Files.size(source);
        URI uri = objectUri(config.objectKey(digest));
        ObjectStorageCredentials resolved = resolveCredentials();
        HttpResponse<Void> response = send("PUT", uri, resolved, digest, Map.of(
                "if-none-match", "*", "x-amz-meta-sha256", digest),
                HttpRequest.BodyPublishers.ofFile(source), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return new StoredArtifact(safePackageId(packageId), referenceFor(config.objectKey(digest)), digest, size);
        }
        if (response.statusCode() == 409 || response.statusCode() == 412) {
            try {
                ArtifactMetadata existing = inspect(referenceFor(config.objectKey(digest)), digest);
                return new StoredArtifact(safePackageId(packageId), referenceFor(config.objectKey(digest)),
                        existing.sha256(), existing.sizeBytes());
            } catch (ArtifactNotFoundException conflict) {
                throw new IOException("artifact storage conflict", conflict);
            }
        }
        throw unavailable("ARTIFACT_STORAGE_HTTP_ERROR");
    }

    @Override
    public PutResult putIfAbsent(String objectKey, byte[] content) {
        String key = relativeObjectKey(objectKey);
        byte[] bytes = content == null ? new byte[0] : content.clone();
        HttpResponse<Void> response = send("PUT", objectUri(key), resolveCredentials(), UNSIGNED_PAYLOAD,
                Map.of("if-none-match", "*"), HttpRequest.BodyPublishers.ofByteArray(bytes),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return new PutResult(true, response.statusCode());
        }
        if (response.statusCode() == 409 || response.statusCode() == 412) {
            return new PutResult(false, response.statusCode());
        }
        throw unavailable("ARTIFACT_STORAGE_HTTP_ERROR");
    }

    @Override
    public boolean readyForUse() {
        if (!config.configured()) return false;
        try {
            resolveCredentials();
            return true;
        } catch (ArtifactStorageUnavailableException unavailable) {
            return false;
        }
    }

    @Override
    public java.util.Optional<byte[]> get(String objectKey) {
        String key = relativeObjectKey(objectKey);
        HttpResponse<byte[]> response = send("GET", objectUri(key), resolveCredentials(), UNSIGNED_PAYLOAD,
                Map.of(), HttpRequest.BodyPublishers.noBody(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) return java.util.Optional.empty();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw unavailable("ARTIFACT_STORAGE_HTTP_ERROR");
        }
        return java.util.Optional.of(response.body());
    }

    @Override
    public void delete(String objectKey) {
        String key = relativeObjectKey(objectKey);
        HttpResponse<Void> response = send("DELETE", objectUri(key), resolveCredentials(), UNSIGNED_PAYLOAD,
                Map.of(), HttpRequest.BodyPublishers.noBody(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 404 && (response.statusCode() < 200 || response.statusCode() >= 300)) {
            throw unavailable("ARTIFACT_STORAGE_HTTP_ERROR");
        }
    }

    @Override
    public ArtifactMetadata inspect(String reference, String expectedSha256) {
        String key = resolveReference(reference);
        validateExpected(expectedSha256);
        HttpResponse<Void> response = send("HEAD", objectUri(key), resolveCredentials(), UNSIGNED_PAYLOAD,
                Map.of(), HttpRequest.BodyPublishers.noBody(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 404) throw notFound();
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw unavailable("ARTIFACT_STORAGE_HTTP_ERROR");
        String metadataHash = response.headers().firstValue("x-amz-meta-sha256").orElse("");
        long size = response.headers().firstValueAsLong("content-length").orElse(-1L);
        if (!metadataHash.isBlank() && !metadataHash.equalsIgnoreCase(expectedSha256.trim())) {
            throw integrityFailure();
        }
        ArtifactResource verified = open(reference, expectedSha256);
        if (size >= 0 && size != verified.sizeBytes()) throw integrityFailure();
        return new ArtifactMetadata(verified.sha256(), verified.sizeBytes());
    }

    @Override
    public ArtifactResource open(String reference, String expectedSha256) {
        String key = resolveReference(reference);
        validateExpected(expectedSha256);
        HttpResponse<byte[]> response = send("GET", objectUri(key), resolveCredentials(), UNSIGNED_PAYLOAD,
                Map.of(), HttpRequest.BodyPublishers.noBody(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) throw notFound();
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw unavailable("ARTIFACT_STORAGE_HTTP_ERROR");
        byte[] bytes = response.body();
        String actual = sha256(bytes);
        if (!actual.equalsIgnoreCase(expectedSha256.trim())) throw integrityFailure();
        verifyZip(bytes);
        return new ArtifactResource(new ByteArrayResource(bytes), actual, bytes.length);
    }

    @Override
    public ArtifactStorageReadiness readiness() {
        if (!config.configured()) {
            return new ArtifactStorageReadiness("object-storage", "NOT_READY",
                    "ARTIFACT_STORAGE_HTTP_NOT_CONFIGURED", "对象存储 HTTP 配置不完整");
        }
        try {
            resolveCredentials();
            return new ArtifactStorageReadiness("object-storage", "DEGRADED",
                    "ARTIFACT_STORAGE_HTTP_CONFIGURED", "S3-compatible 存储已配置，等待目标环境联通验收");
        } catch (ArtifactStorageUnavailableException unavailable) {
            return new ArtifactStorageReadiness("object-storage", "NOT_READY", unavailable.code(),
                    "对象存储凭据引用不可用");
        }
    }

    @Override
    public ArtifactStorageProbeResult probe() {
        Instant started = clock.instant();
        if (!config.configured()) {
            return new ArtifactStorageProbeResult("object-storage", "NOT_CONFIGURED",
                    "ARTIFACT_STORAGE_HTTP_NOT_CONFIGURED", null, elapsedMs(started), started);
        }
        try {
            HttpResponse<Void> response = send("HEAD", bucketUri(), resolveCredentials(), UNSIGNED_PAYLOAD,
                    Map.of("x-skill-center-probe", "v1"), HttpRequest.BodyPublishers.noBody(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new ArtifactStorageProbeResult("object-storage", "REACHABLE",
                        "ARTIFACT_STORAGE_PROBE_OK", response.statusCode(), elapsedMs(started), started);
            }
            return new ArtifactStorageProbeResult("object-storage", "HTTP_ERROR",
                    "ARTIFACT_STORAGE_PROBE_HTTP_ERROR", response.statusCode(), elapsedMs(started), started);
        } catch (ArtifactStorageUnavailableException unavailable) {
            String status = switch (unavailable.code()) {
                case "ARTIFACT_STORAGE_CREDENTIALS_NOT_CONFIGURED" -> "NOT_CONFIGURED";
                case "ARTIFACT_STORAGE_NETWORK_UNAVAILABLE" -> "UNREACHABLE";
                default -> "FAILED";
            };
            return new ArtifactStorageProbeResult("object-storage", status, unavailable.code(),
                    null, elapsedMs(started), started);
        }
    }

    @Override
    public String identity() {
        return IDENTITY;
    }

    private <T> HttpResponse<T> send(String method, URI uri, ObjectStorageCredentials resolved,
                                     String payloadHash, Map<String, String> extraHeaders,
                                     HttpRequest.BodyPublisher body, HttpResponse.BodyHandler<T> handler) {
        Instant now = clock.instant();
        String amzDate = AMZ_DATE.format(now);
        TreeMap<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("host", uri.getAuthority());
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        extraHeaders.forEach(headers::put);
        String signedHeaders = headers.keySet().stream().map(value -> value.toLowerCase(Locale.ROOT))
                .sorted().reduce((left, right) -> left + ";" + right).orElse("");
        String canonicalHeaders = headers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .map(entry -> entry.getKey().toLowerCase(Locale.ROOT) + ":" + normalizeHeader(entry.getValue()) + "\n")
                .reduce("", String::concat);
        String canonicalRequest = method + "\n" + uri.getRawPath() + "\n\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + payloadHash;
        String scope = DATE.format(now) + "/" + config.region() + "/" + SERVICE + "/" + TERMINATOR;
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256(canonicalRequest);
        String signature = HexFormat.of().formatHex(hmac(signingKey(resolved.secretAccessKey(), DATE.format(now)), stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + resolved.accessKeyId() + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).method(method, body)
                .header("Authorization", authorization);
        headers.forEach((name, value) -> {
            // java.net.http owns Host; the URI authority supplies the same value.
            if (!"host".equalsIgnoreCase(name)) builder.header(name, value);
        });
        try {
            return client.send(builder.build(), handler);
        } catch (IOException exception) {
            throw unavailable("ARTIFACT_STORAGE_NETWORK_UNAVAILABLE");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable("ARTIFACT_STORAGE_NETWORK_UNAVAILABLE");
        }
    }

    private URI objectUri(String key) {
        return URI.create(config.endpoint().replaceAll("/+$", "") + "/" + config.bucket() + "/" + key);
    }

    private String relativeObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.startsWith("/")
                || objectKey.contains("..") || !objectKey.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("object key contains unsafe path characters");
        }
        return config.prefix().isBlank() ? objectKey : config.prefix() + "/" + objectKey;
    }

    private URI bucketUri() {
        return URI.create(config.endpoint().replaceAll("/+$", "") + "/" + config.bucket());
    }

    private long elapsedMs(Instant started) {
        return Math.max(0, Duration.between(started, clock.instant()).toMillis());
    }

    private String resolveReference(String reference) {
        try {
            URI uri = URI.create(reference == null ? "" : reference);
            if (!SCHEME.substring(0, SCHEME.length() - 3).equalsIgnoreCase(uri.getScheme())
                    || !config.bucket().equals(uri.getHost()) || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw notFound();
            }
            String key = uri.getRawPath();
            if (key == null || key.length() < 2 || key.substring(1).contains("..")) throw notFound();
            String normalizedKey = key.substring(1);
            String prefix = config.prefix().isBlank() ? "" : config.prefix() + "/";
            String relative = normalizedKey.startsWith(prefix) ? normalizedKey.substring(prefix.length()) : "";
            if (!relative.matches("sha256/[0-9a-fA-F]{64}\\.zip")) throw notFound();
            return normalizedKey;
        } catch (ArtifactNotFoundException exception) {
            throw exception;
        } catch (RuntimeException invalid) {
            throw notFound();
        }
    }

    private ObjectStorageCredentials resolveCredentials() {
        if (credentials == null) throw unavailable("ARTIFACT_STORAGE_CREDENTIALS_NOT_CONFIGURED");
        return credentials.resolve(config.accessKeyIdRef(), config.secretAccessKeyRef());
    }

    private String referenceFor(String key) {
        return SCHEME + config.bucket() + "/" + key;
    }

    private String safePackageId(String packageId) {
        return packageId == null || packageId.isBlank() ? UUID.randomUUID().toString() : packageId;
    }

    private void validateExpected(String expectedSha256) {
        if (expectedSha256 == null || !expectedSha256.trim().matches("[0-9a-fA-F]{64}")) throw integrityFailure();
    }

    private void verifyZip(byte[] bytes) {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            while (input.getNextEntry() != null) {
                while (input.read(buffer) >= 0) {
                    // Consume each entry so CRC errors are detected before the resource is returned.
                }
            }
        } catch (IOException exception) {
            throw integrityFailure();
        }
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = messageDigest();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(messageDigest().digest(bytes));
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest messageDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC unavailable", exception);
        }
    }

    private byte[] signingKey(String secret, String date) {
        byte[] dateKey = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), date);
        byte[] regionKey = hmac(dateKey, config.region());
        byte[] serviceKey = hmac(regionKey, SERVICE);
        return hmac(serviceKey, TERMINATOR);
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private ArtifactNotFoundException notFound() {
        return new ArtifactNotFoundException("published artifact was not found");
    }

    private ArtifactNotFoundException integrityFailure() {
        return new ArtifactNotFoundException("published artifact integrity check failed");
    }

    private ArtifactStorageUnavailableException unavailable(String code) {
        return new ArtifactStorageUnavailableException("object-storage", code);
    }
}
