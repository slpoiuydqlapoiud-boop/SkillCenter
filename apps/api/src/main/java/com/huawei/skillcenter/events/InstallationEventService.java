package com.huawei.skillcenter.events;

import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.InstallationRecord;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InstallationEventService {
    private final GovernanceStore store;
    private final Map<UUID, String> fingerprints = new ConcurrentHashMap<>();

    public InstallationEventService(GovernanceStore store) {
        this.store = store;
    }

    public EventResult ingest(InstallationEvent event) {
        validate(event);
        String fingerprint = event.toString();
        String previous = fingerprints.putIfAbsent(event.eventId(), fingerprint);
        if (previous != null) {
            if (previous.equals(fingerprint)) {
                return new EventResult(event.eventId(), true, true, null);
            }
            return new EventResult(event.eventId(), false, false, "EVENT_ID_CONFLICT");
        }

        InstallationRecord installation = store.snapshot().installations().stream()
                .filter(candidate -> candidate.skillId().equals(event.skillId()))
                .filter(candidate -> candidate.version().equals(event.version()))
                .filter(candidate -> candidate.requestedBy().equals(event.subject().userId()))
                .filter(candidate -> candidate.clientType().equals(event.client().type()))
                .max(Comparator.comparing(InstallationRecord::requestedAt))
                .orElse(null);
        if (installation == null) {
            fingerprints.remove(event.eventId(), fingerprint);
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
        store.updateInstallation(updated, new AuditEvent(UUID.randomUUID().toString(), "INSTALLATION_EVENT_ACCEPTED",
                "INSTALLATION", installation.installationId(), event.subject().userId(), "viewer", "event", occurredAt,
                Map.of("eventId", event.eventId().toString(), "action", event.action(), "outcome", event.outcome())));
        return new EventResult(event.eventId(), true, false, null);
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
        if (!Set.of("install", "upgrade", "downgrade", "uninstall").contains(event.action())) {
            throw new IllegalArgumentException("installation action is invalid");
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
        if ("success".equals(event.outcome()) && event.errorCode() != null) {
            throw new IllegalArgumentException("errorCode is only allowed for failure installation events");
        }
    }

    public record EventResult(UUID eventId, boolean accepted, boolean duplicate, String errorCode) {
    }
}
