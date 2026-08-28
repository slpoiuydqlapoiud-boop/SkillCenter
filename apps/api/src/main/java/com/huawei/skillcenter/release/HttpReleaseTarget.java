package com.huawei.skillcenter.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.quality.ProviderCredentialResolver;
import com.huawei.skillcenter.quality.ProviderHttpRequest;
import com.huawei.skillcenter.quality.ProviderHttpResponse;
import com.huawei.skillcenter.quality.ProviderHttpTransport;
import com.huawei.skillcenter.quality.ProviderUnavailableException;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Metadata-only HTTP target adapter for Runtime/MCP/LLM/CD release systems. */
public final class HttpReleaseTarget implements ReleaseTarget {
    private static final String PROVIDER_ID = "release-target";
    private static final String SCHEMA = "release-target.v1";
    private static final long MAX_DURATION_MS = 86_400_000L;
    private final HttpReleaseTargetConfig config;
    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentials;
    private final ObjectMapper mapper;
    private final Clock clock;

    public HttpReleaseTarget(HttpReleaseTargetConfig config, ProviderHttpTransport transport,
                             ProviderCredentialResolver credentials, ObjectMapper mapper) {
        this(config, transport, credentials, mapper, Clock.systemUTC());
    }

    HttpReleaseTarget(HttpReleaseTargetConfig config, ProviderHttpTransport transport,
                      ProviderCredentialResolver credentials, ObjectMapper mapper, Clock clock) {
        this.config = config == null ? new HttpReleaseTargetConfig("", "", null) : config;
        this.transport = transport;
        this.credentials = credentials;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public ReleaseTargetResult promote(ReleaseRecord release) {
        return execute(release, "PROMOTE");
    }

    @Override
    public ReleaseTargetResult rollback(ReleaseRecord release) {
        return execute(release, "ROLLBACK");
    }

    private ReleaseTargetResult execute(ReleaseRecord release, String action) {
        if (release == null) return ReleaseTargetResult.failure("RELEASE_REQUIRED");
        if (transport == null || credentials == null || config.endpoint().isBlank() || config.credentialRef().isBlank()) {
            return ReleaseTargetResult.failure("RELEASE_TARGET_NOT_CONFIGURED");
        }
        String credential;
        try {
            credential = credentials.resolve(config.credentialRef());
            if (credential == null || credential.isBlank()) {
                return ReleaseTargetResult.failure("RELEASE_TARGET_NOT_CONFIGURED");
            }
        } catch (RuntimeException exception) {
            return ReleaseTargetResult.failure("RELEASE_TARGET_NOT_CONFIGURED");
        }
        Instant started = clock.instant();
        try {
            ProviderHttpResponse response = transport.post(new ProviderHttpRequest(PROVIDER_ID,
                    URI.create(config.endpoint()), credential, requestBody(release, action), config.timeout()));
            WireResponse wire = parse(response);
            long durationMs = boundedDuration(started);
            if ("SUCCEEDED".equals(wire.status())) {
                return new ReleaseTargetResult("SUCCEEDED", "", wire.externalReference(), durationMs, clock.instant());
            }
            return new ReleaseTargetResult("FAILED", wire.reasonCode(), "", durationMs, clock.instant());
        } catch (ProviderUnavailableException exception) {
            return ReleaseTargetResult.failure(mapTransportCode(exception.code()));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return ReleaseTargetResult.failure("RELEASE_TARGET_INVALID_RESPONSE");
        } catch (RuntimeException exception) {
            return ReleaseTargetResult.failure("RELEASE_TARGET_UNAVAILABLE");
        }
    }

    private String requestBody(ReleaseRecord release, String action) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", SCHEMA);
        body.put("action", action);
        body.put("releaseId", release.releaseId());
        body.put("skillId", release.skillId());
        body.put("version", release.version());
        body.put("sha256", release.sha256());
        body.put("targetEnvironment", release.targetEnvironment().name());
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("release target request could not be serialized");
        }
    }

    private WireResponse parse(ProviderHttpResponse response) throws JsonProcessingException {
        if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException("release target response status is invalid");
        }
        WireResponse wire = mapper.readerFor(WireResponse.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(response.body());
        validate(wire);
        return wire;
    }

    private void validate(WireResponse wire) {
        if (wire == null || !("SUCCEEDED".equals(wire.status()) || "FAILED".equals(wire.status()))) {
            throw new IllegalArgumentException("release target status is invalid");
        }
        if ("SUCCEEDED".equals(wire.status())) {
            if (!boundedText(wire.externalReference(), 256)) {
                throw new IllegalArgumentException("release target reference is invalid");
            }
            if (wire.reasonCode() != null && !wire.reasonCode().isBlank()) {
                throw new IllegalArgumentException("successful release target response contains a reason");
            }
        } else if (!stableCode(wire.reasonCode()) || (wire.externalReference() != null && !wire.externalReference().isBlank())) {
            throw new IllegalArgumentException("release target failure response is invalid");
        }
    }

    private static boolean boundedText(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private static boolean stableCode(String value) {
        return value != null && value.length() >= 3 && value.length() <= 64
                && value.matches("[A-Z][A-Z0-9_.:-]{2,63}");
    }

    private long boundedDuration(Instant started) {
        long elapsed;
        try {
            elapsed = Math.max(0, java.time.Duration.between(started, clock.instant()).toMillis());
        } catch (RuntimeException exception) {
            elapsed = 0;
        }
        return Math.min(MAX_DURATION_MS, elapsed);
    }

    private static String mapTransportCode(String code) {
        return switch (code == null ? "" : code.toUpperCase(Locale.ROOT)) {
            case "RATE_LIMITED" -> "RELEASE_TARGET_RATE_LIMITED";
            case "UPSTREAM_TIMEOUT" -> "RELEASE_TARGET_TIMEOUT";
            case "UPSTREAM_RESPONSE_TOO_LARGE" -> "RELEASE_TARGET_RESPONSE_TOO_LARGE";
            case "UPSTREAM_REJECTED" -> "RELEASE_TARGET_REJECTED";
            case "EXTERNAL_ADAPTER_NOT_CONFIGURED" -> "RELEASE_TARGET_NOT_CONFIGURED";
            default -> "RELEASE_TARGET_UNAVAILABLE";
        };
    }

    private record WireResponse(String status, String externalReference, String reasonCode) {
    }
}
