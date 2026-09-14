package com.huawei.skillcenter.events;

import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStateConflictException;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.InstallationRecord;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.regex.Pattern;

@Service
public class InstallationEventService {
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");
    private static final Pattern PRINCIPAL_ID = Pattern.compile("^[A-Za-z0-9._@-]{2,128}$");
    private static final Pattern DEVICE_ID = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");
    private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");
    private static final Set<String> CLIENT_TYPES = Set.of("codex", "department-agent", "skillmd-compatible");

    private final GovernanceStore store;

    public InstallationEventService(GovernanceStore store) {
        this.store = store;
    }

    public EventResult ingest(InstallationEvent event) {
        validate(event);
        String fingerprint = fingerprint(event);
        EventResult persistedResult = findPersistedReceipt(event, fingerprint);
        if (persistedResult != null) {
            return persistedResult;
        }

        InstallationRecord installation = store.snapshot().installations().stream()
                .filter(candidate -> candidate.skillId().equals(event.skillId()))
                .filter(candidate -> candidate.version().equals(event.version()))
                .filter(candidate -> candidate.requestedBy().equals(event.subject().userId()))
                .filter(candidate -> candidate.clientType().equals(event.client().type()))
                .max(Comparator.comparing(InstallationRecord::requestedAt))
                .orElse(null);
        if (installation == null) {
            return new EventResult(event.eventId(), false, false, "INSTALLATION_NOT_FOUND");
        }

        Instant occurredAt = event.occurredAt().toInstant();
        String status = statusFor(event);
        InstallationRecord updated = new InstallationRecord(
                installation.installationId(), installation.manifestId(), installation.skillId(), installation.version(),
                installation.clientType(), installation.clientVersion(), installation.requestedBy(), status,
                installation.requestedAt(), occurredAt, event.subject().teamId(), event.deviceId(), event.method(),
                event.eventId().toString(), event.errorCode(),
                "installed".equals(status) ? occurredAt : installation.installedAt(),
                "removed".equals(status) ? occurredAt : installation.removedAt());
        AuditEvent audit = new AuditEvent(UUID.randomUUID().toString(), "INSTALLATION_EVENT_ACCEPTED",
                "INSTALLATION", installation.installationId(), event.subject().userId(), "viewer", "event", occurredAt,
                Map.of("eventId", event.eventId().toString(), "action", event.action(), "outcome", event.outcome(),
                        "eventFingerprint", fingerprint));
        try {
            store.updateInstallation(updated, audit);
        } catch (GovernanceStateConflictException conflict) {
            store.reload();
            EventResult concurrentResult = findPersistedReceipt(event, fingerprint);
            if (concurrentResult != null) {
                return concurrentResult;
            }
            throw conflict;
        }
        return new EventResult(event.eventId(), true, false, null);
    }

    private EventResult findPersistedReceipt(InstallationEvent event, String fingerprint) {
        List<AuditEvent> audits = store.snapshot().audits();
        for (int index = audits.size() - 1; index >= 0; index--) {
            AuditEvent audit = audits.get(index);
            if (!"INSTALLATION_EVENT_ACCEPTED".equals(audit.action())
                    || !"INSTALLATION".equals(audit.resourceType())
                    || audit.metadata() == null
                    || !event.eventId().toString().equals(audit.metadata().get("eventId"))) {
                continue;
            }
            String storedFingerprint = audit.metadata().get("eventFingerprint");
            if (storedFingerprint == null || storedFingerprint.isBlank() || storedFingerprint.equals(fingerprint)) {
                return new EventResult(event.eventId(), true, true, null);
            }
            return new EventResult(event.eventId(), false, false, "EVENT_ID_CONFLICT");
        }
        return null;
    }

    private String fingerprint(InstallationEvent event) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(event.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public InstallationEventBatchResponse ingestBatch(InstallationEventBatchRequest batch) {
        if (batch == null || batch.batchId() == null || batch.batchId().isBlank()
                || !"1.0".equals(batch.schemaVersion()) || batch.events() == null
                || batch.events().isEmpty() || batch.events().size() > 100) {
            throw new IllegalArgumentException("installation event batch is invalid");
        }
        List<EventResult> results = new ArrayList<>();
        int accepted = 0;
        int duplicates = 0;
        int rejected = 0;
        for (InstallationEvent event : batch.events()) {
            try {
                EventResult result = ingest(event);
                results.add(result);
                if (result.accepted() && result.duplicate()) {
                    duplicates++;
                } else if (result.accepted()) {
                    accepted++;
                } else {
                    rejected++;
                }
            } catch (IllegalArgumentException exception) {
                UUID eventId = event == null ? null : event.eventId();
                results.add(new EventResult(eventId, false, false, "EVENT_SCHEMA_INVALID"));
                rejected++;
            }
        }
        return new InstallationEventBatchResponse(batch.batchId(), accepted, duplicates, rejected, List.copyOf(results));
    }

    private String statusFor(InstallationEvent event) {
        if ("failure".equals(event.outcome())) {
            return "failed";
        }
        return "uninstall".equals(event.action()) ? "removed" : "installed";
    }

    private void validate(InstallationEvent event) {
        if (event == null || event.eventId() == null || event.occurredAt() == null
                || event.skillId() == null || event.version() == null || event.subject() == null
                || event.client() == null || event.deviceId() == null || event.action() == null
                || event.method() == null || event.outcome() == null) {
            throw new IllegalArgumentException("installation event required fields are missing");
        }
        if (!"1.0".equals(event.schemaVersion())) {
            throw new IllegalArgumentException("schemaVersion must be 1.0");
        }
        if (!validSlug(event.skillId()) || !validSemver(event.version())) {
            throw new IllegalArgumentException("skill identity is invalid");
        }
        if (event.fromVersion() != null && !validSemver(event.fromVersion())) {
            throw new IllegalArgumentException("fromVersion is invalid");
        }
        if (event.subject().userId() == null || !PRINCIPAL_ID.matcher(event.subject().userId()).matches()
                || !validSlug(event.subject().teamId())) {
            throw new IllegalArgumentException("subject is invalid");
        }
        if (!CLIENT_TYPES.contains(event.client().type()) || !validSemver(event.client().version())) {
            throw new IllegalArgumentException("client is invalid");
        }
        if (!DEVICE_ID.matcher(event.deviceId()).matches()) {
            throw new IllegalArgumentException("deviceId is invalid");
        }
        if (!Set.of("install", "upgrade", "downgrade", "uninstall").contains(event.action())) {
            throw new IllegalArgumentException("installation action is invalid");
        }
        if (Set.of("upgrade", "downgrade").contains(event.action()) && event.fromVersion() == null) {
            throw new IllegalArgumentException("fromVersion is required for version transitions");
        }
        if (!Set.of("one-click", "cli", "manual-zip").contains(event.method())) {
            throw new IllegalArgumentException("installation method is invalid");
        }
        if (!Set.of("success", "failure").contains(event.outcome())) {
            throw new IllegalArgumentException("installation outcome is invalid");
        }
        if ("failure".equals(event.outcome()) && (event.errorCode() == null || event.errorCode().isBlank())) {
            throw new IllegalArgumentException("errorCode is required for failure installation events");
        }
        if (event.errorCode() != null && !ERROR_CODE.matcher(event.errorCode()).matches()) {
            throw new IllegalArgumentException("errorCode is invalid");
        }
        if ("success".equals(event.outcome()) && event.errorCode() != null) {
            throw new IllegalArgumentException("errorCode is only allowed for failure installation events");
        }
    }

    private boolean validSlug(String value) {
        return value != null && value.length() >= 2 && value.length() <= 64 && SLUG.matcher(value).matches();
    }

    private boolean validSemver(String value) {
        return value != null && value.length() <= 128 && SEMVER.matcher(value).matches();
    }

    public record EventResult(UUID eventId, boolean accepted, boolean duplicate, String errorCode) {
    }
}
