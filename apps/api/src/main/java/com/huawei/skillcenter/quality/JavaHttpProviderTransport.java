package com.huawei.skillcenter.quality;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** JDK-only provider transport. It never logs request or response content. */
public final class JavaHttpProviderTransport implements ProviderHttpTransport {
    private static final int MAX_RESPONSE_BYTES = 256_000;
    private final HttpClient client;

    public JavaHttpProviderTransport() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    JavaHttpProviderTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public ProviderHttpResponse post(ProviderHttpRequest request) {
        HttpRequest outbound = HttpRequest.newBuilder(request.endpoint())
                .timeout(request.timeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + request.bearerCredential())
                .POST(HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = client.send(outbound, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                closeQuietly(response.body());
                return classify(request.providerId(), response.statusCode(), "");
            }
            return classify(request.providerId(), response.statusCode(), readBoundedBody(request.providerId(), response));
        } catch (HttpTimeoutException exception) {
            throw unavailable(request.providerId(), "UPSTREAM_TIMEOUT");
        } catch (IOException exception) {
            throw unavailable(request.providerId(), "TEMPORARY_UNAVAILABLE");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(request.providerId(), "UPSTREAM_INTERRUPTED");
        }
    }

    private String readBoundedBody(String providerId, HttpResponse<InputStream> response) {
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (contentLength > MAX_RESPONSE_BYTES) {
            closeQuietly(response.body());
            throw unavailable(providerId, "UPSTREAM_RESPONSE_TOO_LARGE");
        }
        try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw unavailable(providerId, "UPSTREAM_RESPONSE_TOO_LARGE");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (HttpTimeoutException exception) {
            throw unavailable(providerId, "UPSTREAM_TIMEOUT");
        } catch (IOException exception) {
            throw unavailable(providerId, "TEMPORARY_UNAVAILABLE");
        }
    }

    private void closeQuietly(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
            // Response classification must not expose transport details.
        }
    }

    private ProviderHttpResponse classify(String providerId, int statusCode, String body) {
        if (statusCode == 408 || statusCode == 504) {
            throw unavailable(providerId, "UPSTREAM_TIMEOUT");
        }
        if (statusCode == 429) {
            throw unavailable(providerId, "RATE_LIMITED");
        }
        if (statusCode >= 500) {
            throw unavailable(providerId, "TEMPORARY_UNAVAILABLE");
        }
        if (statusCode >= 400) {
            throw unavailable(providerId, "UPSTREAM_REJECTED");
        }
        return new ProviderHttpResponse(statusCode, body);
    }

    private static ProviderUnavailableException unavailable(String providerId, String code) {
        return new ProviderUnavailableException(providerId, code);
    }
}
