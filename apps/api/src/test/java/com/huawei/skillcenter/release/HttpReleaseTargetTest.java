package com.huawei.skillcenter.release;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.quality.ProviderHttpRequest;
import com.huawei.skillcenter.quality.ProviderHttpResponse;
import com.huawei.skillcenter.quality.ProviderHttpTransport;
import com.huawei.skillcenter.quality.ProviderUnavailableException;
import com.huawei.skillcenter.quality.EnvironmentProviderCredentialResolver;
import com.huawei.skillcenter.quality.JavaHttpProviderTransport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpReleaseTargetTest {
    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void promoteSendsOnlyBoundedReleaseMetadataAndMapsSuccess() {
        AtomicReference<ProviderHttpRequest> captured = new AtomicReference<>();
        ProviderHttpTransport transport = request -> {
            captured.set(request);
            return new ProviderHttpResponse(200,
                    "{\"status\":\"SUCCEEDED\",\"externalReference\":\"deploy/42\",\"reasonCode\":\"\"}");
        };
        HttpReleaseTarget target = target(transport);

        ReleaseTargetResult result = target.promote(release());

        assertThat(result.success()).isTrue();
        assertThat(result.reference()).isEqualTo("deploy/42");
        assertThat(captured).hasValueSatisfying(request -> {
            assertThat(request.providerId()).isEqualTo("release-target");
            assertThat(request.endpoint()).isEqualTo(URI.create("https://deploy.internal/v1/release"));
            assertThat(request.bearerCredential()).isEqualTo("secret-value");
            assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(request.body()).contains("\"schema\":\"release-target.v1\"")
                    .contains("\"action\":\"PROMOTE\"")
                    .contains("\"releaseId\":\"release-1\"")
                    .contains("\"skillId\":\"skill-a\"")
                    .contains("\"version\":\"1.0.0\"")
                    .contains("\"sha256\":\"sha-1\"")
                    .contains("\"targetEnvironment\":\"STAGING\"")
                    .doesNotContain("prompt", "input", "output", "tool", "credential", "secret-value");
        });
    }

    @Test
    void rollbackSendsRollbackAction() {
        AtomicReference<ProviderHttpRequest> captured = new AtomicReference<>();
        HttpReleaseTarget target = target(request -> {
            captured.set(request);
            return new ProviderHttpResponse(200,
                    "{\"status\":\"SUCCEEDED\",\"externalReference\":\"rollback/7\",\"reasonCode\":\"\"}");
        });

        ReleaseTargetResult result = target.rollback(release());

        assertThat(result.success()).isTrue();
        assertThat(captured).hasValueSatisfying(request -> assertThat(request.body())
                .contains("\"action\":\"ROLLBACK\""));
    }

    @Test
    void unknownResponseFieldsFailClosedWithoutLeakingUpstreamBody() {
        HttpReleaseTarget target = target(request -> new ProviderHttpResponse(200,
                "{\"status\":\"SUCCEEDED\",\"externalReference\":\"deploy/42\","
                        + "\"reasonCode\":\"\",\"privatePrompt\":\"do-not-leak\"}"));

        ReleaseTargetResult result = target.promote(release());

        assertThat(result.success()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("RELEASE_TARGET_INVALID_RESPONSE");
        assertThat(result.reasonCode()).doesNotContain("do-not-leak");
    }

    @Test
    void mapsTransportFailuresToStableReleaseReasonCodes() {
        HttpReleaseTarget target = target(request -> {
            throw new ProviderUnavailableException("release-target", "UPSTREAM_RESPONSE_TOO_LARGE");
        });

        ReleaseTargetResult result = target.promote(release());

        assertThat(result.success()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("RELEASE_TARGET_RESPONSE_TOO_LARGE");
    }

    @Test
    void invalidWireStatusAndReferenceFailClosed() {
        HttpReleaseTarget target = target(request -> new ProviderHttpResponse(200,
                "{\"status\":\"SUCCEEDED\",\"externalReference\":\"\",\"reasonCode\":\"\"}"));

        ReleaseTargetResult result = target.promote(release());

        assertThat(result.success()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("RELEASE_TARGET_INVALID_RESPONSE");
    }

    @Test
    void localHttpServerCoversPromoteRollbackBearerAndMetadataOnlyRequest() throws IOException {
        AtomicReference<String> promoteBody = new AtomicReference<>();
        AtomicReference<String> rollbackBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/release", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            boolean rollback = body.contains("\"action\":\"ROLLBACK\"");
            (rollback ? rollbackBody : promoteBody).set(body);
            String response = rollback
                    ? "{\"status\":\"SUCCEEDED\",\"externalReference\":\"rollback/7\",\"reasonCode\":\"\"}"
                    : "{\"status\":\"SUCCEEDED\",\"externalReference\":\"deploy/42\",\"reasonCode\":\"\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            HttpReleaseTarget target = new HttpReleaseTarget(new HttpReleaseTargetConfig(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/release",
                    "secret://env/DEPLOY_TOKEN", Duration.ofSeconds(5)), new JavaHttpProviderTransport(),
                    new EnvironmentProviderCredentialResolver(Map.of("DEPLOY_TOKEN", "secret-value")), mapper);

            assertThat(target.promote(release()).reference()).isEqualTo("deploy/42");
            assertThat(target.rollback(release()).reference()).isEqualTo("rollback/7");
            assertThat(authorization).hasValue("Bearer secret-value");
            assertThat(promoteBody).hasValueSatisfying(body -> assertThat(body)
                    .contains("\"schema\":\"release-target.v1\"")
                    .contains("\"action\":\"PROMOTE\"")
                    .doesNotContain("prompt", "input", "output", "tool", "secret-value"));
            assertThat(rollbackBody).hasValueSatisfying(body -> assertThat(body)
                    .contains("\"action\":\"ROLLBACK\"")
                    .doesNotContain("prompt", "input", "output", "tool", "secret-value"));
        } finally {
            server.stop(0);
        }
    }

    private HttpReleaseTarget target(ProviderHttpTransport transport) {
        return new HttpReleaseTarget(new HttpReleaseTargetConfig(
                "https://deploy.internal/v1/release", "secret://env/DEPLOY_TOKEN", Duration.ofSeconds(10)),
                transport, reference -> "secret-value", mapper);
    }

    private ReleaseRecord release() {
        return ReleaseRecord.request("release-1", "skill-a", "1.0.0", "sha-1", ReleaseEnvironment.STAGING,
                ReleaseGateSnapshot.passed(NOW), "idem-1", "admin", NOW);
    }
}
