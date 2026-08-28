package com.huawei.skillcenter.distribution;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3CompatibleArtifactStorageTest {
    private HttpServer server;
    private AtomicReference<RequestSnapshot> request;

    @BeforeEach
    void setUp() throws IOException {
        request = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void storesContentAddressedObjectWithSignedImmutablePut() throws Exception {
        Path source = zip("skill.md", "hello");
        S3CompatibleArtifactStorage storage = storage();

        ArtifactStorage.StoredArtifact stored = storage.store(source, "package-1");

        assertThat(stored.reference()).startsWith("s3://bucket/");
        assertThat(stored.sha256()).hasSize(64);
        assertThat(request.get().method()).isEqualTo("PUT");
        assertThat(request.get().path()).contains("/bucket/skills/sha256/" + stored.sha256() + ".zip");
        assertThat(request.get().headers().getFirst("Authorization")).startsWith("AWS4-HMAC-SHA256 ");
        assertThat(request.get().headers().getFirst("If-None-Match")).isEqualTo("*");
        assertThat(request.get().headers().getFirst("x-amz-meta-sha256")).isEqualTo(stored.sha256());
        assertThat(request.get().body()).isEqualTo(Files.readAllBytes(source));
    }

    @Test
    void opensObjectOnlyAfterHashAndZipVerification() throws Exception {
        Path source = zip("skill.md", "hello");
        String sha256 = sha256(source);
        byte[] bytes = Files.readAllBytes(source);
        server.removeContext("/");
        server.createContext("/", exchange -> {
            request.set(new RequestSnapshot(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders(), new byte[0]));
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
                exchange.getResponseHeaders().set("x-amz-meta-sha256", sha256);
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            }
        });
        S3CompatibleArtifactStorage storage = storage();

        ArtifactStorage.ArtifactMetadata metadata = storage.inspect("s3://bucket/skills/sha256/" + sha256 + ".zip", sha256);
        ArtifactStorage.ArtifactResource opened = storage.open(
                "s3://bucket/skills/sha256/" + sha256 + ".zip", sha256);

        assertThat(metadata.sha256()).isEqualTo(sha256);
        assertThat(metadata.sizeBytes()).isEqualTo(bytes.length);
        assertThat(opened.sha256()).isEqualTo(sha256);
        assertThat(opened.resource().getContentAsByteArray()).isEqualTo(bytes);
    }

    @Test
    void rejectsRemoteBytesWhenRecordedHashDoesNotMatch() throws Exception {
        Path source = zip("skill.md", "hello");
        String expected = sha256(source);
        byte[] corrupt = "not-a-zip".getBytes(StandardCharsets.UTF_8);
        server.removeContext("/");
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("x-amz-meta-sha256", expected);
            exchange.sendResponseHeaders(200, corrupt.length);
            exchange.getResponseBody().write(corrupt);
            exchange.close();
        });
        S3CompatibleArtifactStorage storage = storage();

        assertThatThrownBy(() -> storage.open("s3://bucket/skills/sha256/" + expected + ".zip", expected))
                .isInstanceOf(ArtifactNotFoundException.class)
                .hasMessageContaining("integrity");
    }

    @Test
    void inspectDoesNotTrustMetadataWhenRemoteBytesAreCorrupt() throws Exception {
        Path source = zip("skill.md", "hello");
        String expected = sha256(source);
        byte[] corrupt = "not-a-zip".getBytes(StandardCharsets.UTF_8);
        server.removeContext("/");
        server.createContext("/", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(corrupt.length));
                exchange.getResponseHeaders().set("x-amz-meta-sha256", expected);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, corrupt.length);
            exchange.getResponseBody().write(corrupt);
            exchange.close();
        });
        S3CompatibleArtifactStorage storage = storage();

        assertThatThrownBy(() -> storage.inspect("s3://bucket/skills/sha256/" + expected + ".zip", expected))
                .isInstanceOf(ArtifactNotFoundException.class)
                .hasMessageContaining("integrity");
    }

    @Test
    void rejectsReferencesOutsideConfiguredPrefix() {
        S3CompatibleArtifactStorage storage = storage();

        assertThatThrownBy(() -> storage.open("s3://bucket/other/secret.zip", "a".repeat(64)))
                .isInstanceOf(ArtifactNotFoundException.class);
    }

    @Test
    void readinessFailsClosedWhenCredentialReferencesCannotResolve() {
        ObjectStorageConfig config = config();
        S3CompatibleArtifactStorage storage = new S3CompatibleArtifactStorage(
                config, new EnvironmentObjectStorageCredentialResolver(Map.of()),
                HttpClient.newHttpClient(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        assertThat(storage.readiness().status()).isEqualTo("NOT_READY");
        assertThat(storage.readiness().reasonCode()).isEqualTo("ARTIFACT_STORAGE_CREDENTIALS_NOT_CONFIGURED");
    }

    @Test
    void probeChecksOnlyTheBucketControlPlane() {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            request.set(new RequestSnapshot(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders(), exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        S3CompatibleArtifactStorage storage = storage();

        ArtifactStorageProbeResult result = storage.probe();

        assertThat(result.status()).isEqualTo("REACHABLE");
        assertThat(result.reasonCode()).isEqualTo("ARTIFACT_STORAGE_PROBE_OK");
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(request.get().method()).isEqualTo("HEAD");
        assertThat(request.get().path()).isEqualTo("/bucket");
        assertThat(request.get().body()).isEmpty();
    }

    @Test
    void exposesRawImmutableObjectOperationsForResumableChunks() {
        S3ObjectClient storage = storage();

        S3ObjectClient.PutResult stored = storage.putIfAbsent("resumable/upload-1/chunk-0", "abc".getBytes(StandardCharsets.UTF_8));
        storage.get("resumable/upload-1/chunk-0");
        storage.delete("resumable/upload-1/chunk-0");

        assertThat(stored.stored()).isTrue();
        assertThat(request.get().method()).isEqualTo("DELETE");
        assertThat(request.get().path()).isEqualTo("/bucket/skills/resumable/upload-1/chunk-0");
    }

    private S3CompatibleArtifactStorage storage() {
        return new S3CompatibleArtifactStorage(
                config(), new EnvironmentObjectStorageCredentialResolver(Map.of(
                        "ACCESS_KEY", "access-key",
                        "SECRET_KEY", "secret-key")),
                HttpClient.newHttpClient(), Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneOffset.UTC));
    }

    private ObjectStorageConfig config() {
        return new ObjectStorageConfig("http://127.0.0.1:" + server.getAddress().getPort(),
                "bucket", "us-east-1", "skills", "secret://env/ACCESS_KEY", "secret://env/SECRET_KEY");
    }

    private Path zip(String name, String content) throws IOException {
        Path path = Files.createTempFile("artifact-", ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry(name));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return path;
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        request.set(new RequestSnapshot(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                exchange.getRequestHeaders(), body));
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private String sha256(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private record RequestSnapshot(String method, String path,
                                   com.sun.net.httpserver.Headers headers, byte[] body) {
    }
}
