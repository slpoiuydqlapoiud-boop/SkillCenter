package com.huawei.skillcenter.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.skillcenter.quality.ProviderCredentialResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HexFormat;

/** OpenSearch/Elasticsearch-compatible HTTP search projection with bounded, redacted I/O. */
public final class HttpSkillSearchIndex implements SkillSearchIndex, SkillSearchRemoteHealth {
    private static final int MAX_CANDIDATES = 5_000;
    private static final int MAX_REQUEST_BYTES = 1_024 * 1_024;
    private static final Set<String> ALLOWED_MATCHED_FIELDS = Set.of(
            "id", "name", "tags", "description", "team", "category");

    private final String endpoint;
    private final String indexName;
    private final String credentialRef;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final ProviderCredentialResolver credentials;
    private final Clock clock;
    private final Duration probeTtl;
    private final String probeIdentity;
    private volatile Snapshot snapshot = new Snapshot("NOT_READY", 0, 0, "", null, "");
    private volatile SkillSearchProbeResult lastProbe;

    public HttpSkillSearchIndex(String endpoint, String indexName, String credentialRef,
                                Duration connectTimeout, Duration requestTimeout, int maxResponseBytes,
                                HttpClient client, ObjectMapper mapper,
                                ProviderCredentialResolver credentials, Clock clock) {
        this(endpoint, indexName, credentialRef, connectTimeout, requestTimeout, maxResponseBytes, client, mapper,
                credentials, clock, Duration.ofSeconds(300));
    }

    public HttpSkillSearchIndex(String endpoint, String indexName, String credentialRef,
                                Duration connectTimeout, Duration requestTimeout, int maxResponseBytes,
                                HttpClient client, ObjectMapper mapper,
                                ProviderCredentialResolver credentials, Clock clock, Duration probeTtl) {
        this.endpoint = endpoint == null ? "" : endpoint.strip();
        this.indexName = indexName == null ? "" : indexName.strip();
        this.credentialRef = credentialRef == null ? "" : credentialRef.strip();
        this.requestTimeout = validDuration(requestTimeout, "requestTimeout");
        if (maxResponseBytes < 1 || maxResponseBytes > 256_000) {
            throw new IllegalArgumentException("maxResponseBytes must be between 1 and 256000");
        }
        this.maxResponseBytes = maxResponseBytes;
        this.client = client == null
                ? HttpClient.newBuilder().connectTimeout(validDuration(connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NEVER).build() : client;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.credentials = credentials == null ? reference -> "" : credentials;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.probeTtl = validProbeTtl(probeTtl);
        this.probeIdentity = configurationFingerprint(this.endpoint, this.indexName, this.credentialRef);
    }

    @Override
    public String backend() {
        return "opensearch";
    }

    @Override
    public String probeIdentity() {
        return probeIdentity;
    }

    @Override
    public SkillSearchIndexStatus status() {
        Snapshot current = snapshot;
        return new SkillSearchIndexStatus(current.state(), current.revision(), current.documentCount(),
                current.sourceHash(), Integer.toString(current.revision()), current.indexedAt(), current.reasonCode());
    }

    @Override
    public synchronized SkillSearchRebuildResult rebuild(List<SkillSearchDocument> documents, String sourceHash) {
        if (documents == null) throw new IllegalArgumentException("documents is required");
        String validatedHash = SkillSearchDocument.boundedRequired(sourceHash, "sourceHash", 256);
        Snapshot current = snapshot;
        if (current.revision() > 0 && validatedHash.equals(current.sourceHash())) {
            snapshot = new Snapshot("READY", current.revision(), current.documentCount(), current.sourceHash(),
                    current.indexedAt(), "");
            return new SkillSearchRebuildResult(current.revision(), current.documentCount(), current.sourceHash(),
                    Integer.toString(current.revision()));
        }
        validateConfiguration();
        List<SkillSearchDocument> boundedDocuments = validateDocuments(documents);
        String body = bulkBody(boundedDocuments);
        try {
            JsonNode response = post("_bulk", body);
            if (!response.path("errors").isBoolean() || response.path("errors").booleanValue()) {
                throw remote("SEARCH_INDEX_RESPONSE_INVALID");
            }
            current = snapshot;
            Snapshot committed = new Snapshot("READY", current.revision() + 1, boundedDocuments.size(),
                    validatedHash, Instant.now(clock), "");
            snapshot = committed;
            return new SkillSearchRebuildResult(committed.revision(), committed.documentCount(),
                    committed.sourceHash(), Integer.toString(committed.revision()));
        } catch (SkillSearchIndexRemoteException exception) {
            markFailure(exception.reasonCode());
            throw exception;
        } catch (RuntimeException exception) {
            markFailure("SEARCH_INDEX_RESPONSE_INVALID");
            throw remote("SEARCH_INDEX_RESPONSE_INVALID");
        }
    }

    @Override
    public synchronized void invalidate(String reason) {
        SkillSearchDocument.bounded(reason, "reason", 256);
        Snapshot current = snapshot;
        snapshot = new Snapshot("STALE", current.revision(), current.documentCount(), current.sourceHash(),
                current.indexedAt(), "SEARCH_INDEX_STALE");
    }

    @Override
    public List<SkillSearchHit> search(SkillSearchQuery query) {
        if (query == null) throw new IllegalArgumentException("query is required");
        validateConfiguration();
        if (!probeFresh()) throw remote("SEARCH_INDEX_PROBE_EXPIRED");
        try {
            JsonNode response = post("_search", searchBody(query));
            JsonNode hits = response.path("hits").path("hits");
            if (!hits.isArray()) throw remote("SEARCH_INDEX_RESPONSE_INVALID");
            List<SkillSearchHit> result = new ArrayList<>();
            for (JsonNode hit : hits) {
                if (result.size() == MAX_CANDIDATES) break;
                String skillId = text(hit, "_id");
                JsonNode scoreNode = hit.get("_score");
                if (skillId.isBlank() || scoreNode == null || !scoreNode.isNumber()
                        || !Double.isFinite(scoreNode.doubleValue()) || scoreNode.doubleValue() < 0) {
                    throw remote("SEARCH_INDEX_RESPONSE_INVALID");
                }
                result.add(new SkillSearchHit(skillId, scoreNode.doubleValue(), matchedFields(hit)));
            }
            return List.copyOf(result);
        } catch (SkillSearchIndexRemoteException exception) {
            markFailure(exception.reasonCode());
            throw exception;
        } catch (RuntimeException exception) {
            markFailure("SEARCH_INDEX_RESPONSE_INVALID");
            throw remote("SEARCH_INDEX_RESPONSE_INVALID");
        }
    }

    @Override
    public SkillSearchProbeResult probe() {
        Instant checkedAt = Instant.now(clock);
        if (endpoint.isBlank() || indexName.isBlank() || credentialRef.isBlank()) {
            return recordProbe(new SkillSearchProbeResult("opensearch", "NOT_CONFIGURED",
                    "SEARCH_INDEX_ENDPOINT_NOT_CONFIGURED", null, 0, checkedAt));
        }
        URI uri;
        String credential;
        try {
            uri = baseUri();
            credential = credentials.resolve(credentialRef);
        } catch (RuntimeException exception) {
            return recordProbe(new SkillSearchProbeResult("opensearch", "NOT_CONFIGURED",
                    "SEARCH_INDEX_CREDENTIAL_UNAVAILABLE", null, 0, checkedAt));
        }
        if (credential == null || credential.isBlank()) {
            return recordProbe(new SkillSearchProbeResult("opensearch", "NOT_CONFIGURED",
                    "SEARCH_INDEX_CREDENTIAL_UNAVAILABLE", null, 0, checkedAt));
        }
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + credential)
                    .header("X-Skill-Center-Probe", "v1")
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            String state = status >= 200 && status < 300 ? "REACHABLE" : "HTTP_ERROR";
            String reason = state.equals("REACHABLE") ? "SEARCH_INDEX_PROBE_OK" : "SEARCH_INDEX_PROBE_HTTP_ERROR";
            return recordProbe(new SkillSearchProbeResult("opensearch", state, reason, status,
                    elapsedMs(started), checkedAt));
        } catch (HttpTimeoutException exception) {
            return recordProbe(failedProbe("SEARCH_INDEX_PROBE_TIMEOUT", started, checkedAt));
        } catch (IOException exception) {
            return recordProbe(failedProbe("SEARCH_INDEX_PROBE_UNREACHABLE", started, checkedAt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return recordProbe(failedProbe("SEARCH_INDEX_PROBE_INTERRUPTED", started, checkedAt));
        } catch (RuntimeException exception) {
            return recordProbe(failedProbe("SEARCH_INDEX_PROBE_FAILED", started, checkedAt));
        }
    }

    @Override
    public boolean probeFresh() {
        SkillSearchProbeResult current = lastProbe;
        if (current == null || !"REACHABLE".equals(current.status())) return false;
        Duration age = Duration.between(current.checkedAt(), Instant.now(clock));
        return !age.isNegative() && age.compareTo(probeTtl) <= 0;
    }

    private JsonNode post(String operation, String body) {
        URI uri = endpoint(operation);
        String credential;
        try {
            credential = credentials.resolve(credentialRef);
        } catch (RuntimeException exception) {
            throw remote("SEARCH_INDEX_CREDENTIAL_UNAVAILABLE");
        }
        if (credential == null || credential.isBlank()) throw remote("SEARCH_INDEX_CREDENTIAL_UNAVAILABLE");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-ndjson")
                .header("Authorization", "Bearer " + credential)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            String responseBody = readBounded(response);
            if (response.statusCode() == 408 || response.statusCode() == 504) {
                throw remote("SEARCH_INDEX_UPSTREAM_TIMEOUT");
            }
            if (response.statusCode() == 429) throw remote("SEARCH_INDEX_RATE_LIMITED");
            if (response.statusCode() >= 500) throw remote("SEARCH_INDEX_UPSTREAM_UNAVAILABLE");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw remote("SEARCH_INDEX_UPSTREAM_REJECTED");
            }
            try {
                return mapper.readTree(responseBody);
            } catch (IOException exception) {
                throw remote("SEARCH_INDEX_RESPONSE_INVALID");
            }
        } catch (HttpTimeoutException exception) {
            throw remote("SEARCH_INDEX_UPSTREAM_TIMEOUT");
        } catch (IOException exception) {
            throw remote("SEARCH_INDEX_UPSTREAM_UNAVAILABLE");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw remote("SEARCH_INDEX_UPSTREAM_INTERRUPTED");
        }
    }

    private String readBounded(HttpResponse<InputStream> response) throws IOException {
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (contentLength > maxResponseBytes) {
            close(response.body());
            throw remote("SEARCH_INDEX_RESPONSE_TOO_LARGE");
        }
        try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxResponseBytes) throw remote("SEARCH_INDEX_RESPONSE_TOO_LARGE");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private URI endpoint(String operation) {
        URI base = baseUri();
        return URI.create(base.toString().replaceAll("/+$", "") + "/" + operation);
    }

    private URI baseUri() {
        if (endpoint.isBlank() || indexName.isBlank() || credentialRef.isBlank()) {
            throw remote("SEARCH_INDEX_ENDPOINT_NOT_CONFIGURED");
        }
        if (!indexName.matches("[A-Za-z0-9._-]{1,128}")) throw remote("SEARCH_INDEX_INVALID_ENDPOINT");
        try {
            URI base = URI.create(endpoint.replaceAll("/+$", "") + "/" + indexName);
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null
                    || base.getQuery() != null || base.getFragment() != null) {
                throw remote("SEARCH_INDEX_INVALID_ENDPOINT");
            }
            return base;
        } catch (IllegalArgumentException exception) {
            throw remote("SEARCH_INDEX_INVALID_ENDPOINT");
        }
    }

    private List<SkillSearchDocument> validateDocuments(List<SkillSearchDocument> documents) {
        Set<String> ids = new HashSet<>();
        List<SkillSearchDocument> result = new ArrayList<>();
        for (SkillSearchDocument document : documents) {
            if (document == null || !ids.add(document.skillId())) {
                throw new IllegalArgumentException("documents must not contain null or duplicate skillId values");
            }
            result.add(document);
        }
        return List.copyOf(result);
    }

    private String bulkBody(List<SkillSearchDocument> documents) {
        try {
            StringBuilder body = new StringBuilder();
            for (SkillSearchDocument document : documents) {
                ObjectNode action = mapper.createObjectNode();
                action.putObject("index").put("_id", document.skillId());
                appendJson(body, action);
                ObjectNode source = mapper.createObjectNode();
                source.put("skillId", document.skillId()).put("name", document.name())
                        .put("description", document.description()).put("team", document.team())
                        .put("category", document.category()).put("status", document.status())
                        .put("risk", document.risk()).put("latestVersion", document.latestVersion())
                        .put("visibility", document.visibility()).put("ownerTeamId", document.ownerTeamId());
                ArrayNode tags = source.putArray("tags");
                document.tags().forEach(tags::add);
                appendJson(body, source);
            }
            if (body.toString().getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
                throw remote("SEARCH_INDEX_REQUEST_TOO_LARGE");
            }
            return body.toString();
        } catch (RuntimeException exception) {
            if (exception instanceof SkillSearchIndexRemoteException remote) throw remote;
            throw remote("SEARCH_INDEX_REQUEST_INVALID");
        }
    }

    private String searchBody(SkillSearchQuery query) {
        try {
            ObjectNode body = mapper.createObjectNode().put("size", MAX_CANDIDATES);
            ObjectNode bool = body.putObject("query").putObject("bool");
            ArrayNode must = bool.putArray("must");
            if (query.text().isBlank()) {
                must.add(mapper.createObjectNode().set("match_all", mapper.createObjectNode()));
            }
            else must.add(mapper.createObjectNode().putObject("multi_match").put("query", query.text()));
            ArrayNode filters = bool.putArray("filter");
            addTerm(filters, "category", query.category());
            addTerm(filters, "status", query.status());
            addTerm(filters, "risk", query.risk());
            if (body.toString().getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
                throw remote("SEARCH_INDEX_REQUEST_TOO_LARGE");
            }
            return body.toString();
        } catch (RuntimeException exception) {
            if (exception instanceof SkillSearchIndexRemoteException remote) throw remote;
            throw remote("SEARCH_INDEX_REQUEST_INVALID");
        }
    }

    private void addTerm(ArrayNode filters, String field, String value) {
        if (value != null && !value.isBlank()) {
            filters.add(mapper.createObjectNode().putObject("term").put(field, value));
        }
    }

    private void appendJson(StringBuilder body, JsonNode node) {
        try {
            body.append(mapper.writeValueAsString(node)).append('\n');
        } catch (IOException exception) {
            throw remote("SEARCH_INDEX_REQUEST_INVALID");
        }
    }

    private List<String> matchedFields(JsonNode hit) {
        JsonNode fields = hit.get("matchedFields");
        if (fields == null) fields = hit.get("matched_fields");
        if (fields == null) return List.of();
        if (!fields.isArray() || fields.size() > 6) {
            throw remote("SEARCH_INDEX_RESPONSE_INVALID");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode field : fields) {
            if (!field.isTextual() || !ALLOWED_MATCHED_FIELDS.contains(field.textValue())
                    || !result.add(field.textValue())) throw remote("SEARCH_INDEX_RESPONSE_INVALID");
        }
        return result;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : "";
    }

    private void validateConfiguration() {
        baseUri();
    }

    private void markFailure(String reason) {
        Snapshot current = snapshot;
        String state = current.revision() == 0 ? "NOT_READY" : "DEGRADED";
        snapshot = new Snapshot(state, current.revision(), current.documentCount(), current.sourceHash(),
                current.indexedAt(), reason);
    }

    private static SkillSearchIndexRemoteException remote(String reasonCode) {
        return new SkillSearchIndexRemoteException(reasonCode);
    }

    private static Duration validDuration(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(Duration.ofSeconds(120)) > 0) {
            throw new IllegalArgumentException(field + " must be between 1ms and 120s");
        }
        return value;
    }

    private static Duration validProbeTtl(Duration value) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("probeTtl must be between 1ms and 1d");
        }
        return value;
    }

    private SkillSearchProbeResult recordProbe(SkillSearchProbeResult result) {
        lastProbe = result;
        return result;
    }

    private SkillSearchProbeResult failedProbe(String reason, long started, Instant checkedAt) {
        return new SkillSearchProbeResult("opensearch", "FAILED", reason, null, elapsedMs(started), checkedAt);
    }

    private static String configurationFingerprint(String endpoint, String indexName, String credentialRef) {
        String input = endpoint + "\n" + indexName + "\n" + credentialRef;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static void close(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
            // Do not expose transport details.
        }
    }

    private record Snapshot(String state, int revision, int documentCount, String sourceHash,
                            Instant indexedAt, String reasonCode) {
    }
}
