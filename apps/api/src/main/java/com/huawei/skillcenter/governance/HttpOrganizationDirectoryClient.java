package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Strict, bounded HTTP adapter for the organization-directory.v1 snapshot. */
public final class HttpOrganizationDirectoryClient implements OrganizationDirectoryClient {
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "source", "revision", "fetchedAt", "teams");
    private static final Set<String> TEAM_FIELDS = Set.of("teamId", "name", "status", "memberUserIds");
    private final OrganizationDirectoryProperties properties;
    private final OrganizationDirectoryCredentialResolver credentials;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public HttpOrganizationDirectoryClient(OrganizationDirectoryProperties properties,
                                           OrganizationDirectoryCredentialResolver credentials,
                                           HttpClient client, ObjectMapper mapper, Clock clock) {
        if (properties == null || credentials == null || client == null || mapper == null) {
            throw new IllegalArgumentException("organization directory client is invalid");
        }
        properties.validate();
        this.properties = properties;
        this.credentials = credentials;
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public OrganizationDirectorySnapshot fetch() {
        if (!"http".equals(properties.getMode())) {
            throw unavailable("DIRECTORY_NOT_CONFIGURED");
        }
        String secret;
        try {
            secret = credentials.resolve(properties.getCredentialRef());
        } catch (Exception exception) {
            throw unavailable("DIRECTORY_CREDENTIAL_UNAVAILABLE");
        }
        if (secret == null || secret.isBlank()) throw unavailable("DIRECTORY_CREDENTIAL_UNAVAILABLE");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getEndpoint()))
                    .timeout(java.time.Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + secret)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) throw unavailable("DIRECTORY_HTTP_STATUS");
                byte[] bytes = readBounded(body, properties.getMaxResponseBytes());
                return parse(bytes);
            }
        } catch (OrganizationDirectoryUnavailableException exception) {
            throw exception;
        } catch (java.net.http.HttpTimeoutException exception) {
            throw unavailable("DIRECTORY_TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable("DIRECTORY_INTERRUPTED");
        } catch (Exception exception) {
            throw unavailable("DIRECTORY_UNREACHABLE");
        }
    }

    private byte[] readBounded(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maxBytes) throw unavailable("DIRECTORY_RESPONSE_TOO_LARGE");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private OrganizationDirectorySnapshot parse(byte[] bytes) {
        try {
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject()) throw invalid();
            rejectUnknown(root, ROOT_FIELDS);
            String schema = text(root, "schemaVersion");
            String source = text(root, "source");
            String revision = text(root, "revision");
            Instant fetchedAt = Instant.parse(text(root, "fetchedAt"));
            JsonNode teams = root.get("teams");
            if (teams == null || !teams.isArray() || teams.size() > OrganizationDirectorySnapshot.MAX_TEAMS) {
                throw invalid();
            }
            List<OrganizationDirectorySnapshot.Team> parsed = new ArrayList<>();
            for (JsonNode team : teams) {
                if (team == null || !team.isObject()) throw invalid();
                rejectUnknown(team, TEAM_FIELDS);
                JsonNode members = team.get("memberUserIds");
                if (members == null || !members.isArray()) throw invalid();
                List<String> memberIds = new ArrayList<>();
                for (JsonNode member : members) {
                    if (member == null || !member.isTextual()) throw invalid();
                    memberIds.add(member.asText());
                }
                parsed.add(new OrganizationDirectorySnapshot.Team(text(team, "teamId"), text(team, "name"),
                        text(team, "status"), memberIds));
            }
            return new OrganizationDirectorySnapshot(schema, source, revision, fetchedAt, parsed);
        } catch (OrganizationDirectoryUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        return value.asText();
    }

    private void rejectUnknown(JsonNode object, Set<String> allowed) {
        Iterator<String> fields = object.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) throw invalid();
        }
    }

    private OrganizationDirectoryUnavailableException invalid() {
        return unavailable("DIRECTORY_INVALID_RESPONSE");
    }

    private OrganizationDirectoryUnavailableException unavailable(String reason) {
        return new OrganizationDirectoryUnavailableException(reason);
    }
}
