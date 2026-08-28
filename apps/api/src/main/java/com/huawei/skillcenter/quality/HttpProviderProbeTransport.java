package com.huawei.skillcenter.quality;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;

/** Performs a bounded GET and records only the HTTP status or a stable failure category. */
@Component
public class HttpProviderProbeTransport implements ProviderProbeTransport {
    private final HttpClient client;

    public HttpProviderProbeTransport() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1500))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public ProviderProbeTransportResult probe(String endpoint, Duration timeout) {
        Instant started = Instant.now();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("X-Skill-Center-Provider-Probe", "v1")
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return ProviderProbeTransportResult.http(response.statusCode(), elapsedMs(started));
        } catch (HttpTimeoutException exception) {
            return ProviderProbeTransportResult.failure("PROBE_TIMEOUT", elapsedMs(started));
        } catch (ConnectException exception) {
            return ProviderProbeTransportResult.failure("PROBE_UNREACHABLE", elapsedMs(started));
        } catch (IOException exception) {
            return ProviderProbeTransportResult.failure("PROBE_UNREACHABLE", elapsedMs(started));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ProviderProbeTransportResult.failure("PROBE_INTERRUPTED", elapsedMs(started));
        } catch (RuntimeException exception) {
            return ProviderProbeTransportResult.failure("PROBE_FAILED", elapsedMs(started));
        }
    }

    private long elapsedMs(Instant started) {
        return Math.max(0, Duration.between(started, Instant.now()).toMillis());
    }
}
