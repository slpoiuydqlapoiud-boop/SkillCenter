package com.huawei.skillcenter.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PersistenceControlService {
    private final PersistenceArtifactCatalog catalog;
    private final PersistenceIntegrityService integrityService;
    private final PersistenceSnapshotService snapshotService;
    private final PersistenceControlProperties properties;
    private final Path configuredRoot;
    private final boolean startupGateEnabled;
    private final PersistenceBackend backend;
    private volatile PersistenceStatusView startupStatus;

    public PersistenceControlService(PersistenceArtifactCatalog catalog,
                                     PersistenceIntegrityService integrityService,
                                     PersistenceSnapshotService snapshotService) {
        this(catalog, integrityService, snapshotService, null, null, false, new JsonPersistenceBackend());
    }

    @Autowired
    public PersistenceControlService(PersistenceArtifactCatalog catalog,
                                     PersistenceIntegrityService integrityService,
                                     PersistenceSnapshotService snapshotService,
                                     PersistenceControlProperties properties,
                                     @Value("${skill-center.persistence.configured-root:${user.dir}}") String configuredRoot,
                                     PersistenceBackend backend) {
        this(catalog, integrityService, snapshotService, properties, configuredRootPath(configuredRoot), true, backend);
    }

    PersistenceControlService(PersistenceArtifactCatalog catalog,
                              PersistenceIntegrityService integrityService,
                              PersistenceSnapshotService snapshotService,
                              PersistenceControlProperties properties,
                              Path configuredRoot,
                              boolean startupGateEnabled) {
        this(catalog, integrityService, snapshotService, properties, configuredRoot, startupGateEnabled,
                new JsonPersistenceBackend());
    }

    PersistenceControlService(PersistenceArtifactCatalog catalog,
                              PersistenceIntegrityService integrityService,
                              PersistenceSnapshotService snapshotService,
                              PersistenceControlProperties properties,
                              Path configuredRoot,
                              boolean startupGateEnabled,
                              PersistenceBackend backend) {
        if (catalog == null || integrityService == null || snapshotService == null || backend == null) {
            throw new IllegalArgumentException("persistence services must not be null");
        }
        this.catalog = catalog;
        this.integrityService = integrityService;
        this.snapshotService = snapshotService;
        this.properties = properties;
        this.configuredRoot = configuredRoot == null ? null : configuredRoot.toAbsolutePath().normalize();
        this.startupGateEnabled = startupGateEnabled;
        this.backend = backend;
    }

    public PersistenceStatusView status() {
        StatusCalculation calculation = calculateStatus();
        List<StatusWithCritical> observed = calculation.observed();
        List<PersistenceArtifactStatusView> artifacts = observed.stream().map(this::statusView).toList();
        return new PersistenceStatusView(
                overallState(observed, calculation.controlPlaneReasonCodes()),
                artifacts,
                calculation.controlPlaneReasonCodes());
    }

    @PostConstruct
    void onStartup() {
        validateStartup();
    }

    void validateStartup() {
        PersistenceStatusView current = status();
        startupStatus = current;
        if (startupGateEnabled
                && "FAIL_CLOSED".equals(current.overall())
                && isFailClosedMode()) {
            throw new IllegalStateException(
                    "Persistence startup gate failed: "
                            + String.join(",", startupFailureReasonCodes(current)));
        }
    }

    PersistenceStatusView startupStatus() {
        return startupStatus == null ? status() : startupStatus;
    }

    public PersistenceSnapshotView createSnapshot() {
        return snapshotView(snapshotService.createSnapshot());
    }

    public List<PersistenceSnapshotView> listSnapshots() {
        return snapshotService.listSnapshots().stream().map(this::snapshotView).toList();
    }

    public PersistenceSnapshotView getSnapshot(String snapshotId) {
        return snapshotView(snapshotService.getSnapshot(snapshotId));
    }

    public PersistencePreflightView restorePreflight(String snapshotId) {
        PersistenceSnapshotService.RestorePreflightResult result = snapshotService.restorePreflight(snapshotId);
        return new PersistencePreflightView(snapshotId, result.status(), result.reasonCode(), result.artifactCount());
    }

    private StatusCalculation calculateStatus() {
        List<StatusWithCritical> observed = new ArrayList<>();
        Set<String> controlPlaneReasonCodes = new LinkedHashSet<>();
        if (properties != null
                && properties.getStartupMode() != null
                && !properties.getStartupMode().isBlank()
                && !"fail-closed".equalsIgnoreCase(properties.getStartupMode())
                && !"report-only".equalsIgnoreCase(properties.getStartupMode())) {
            controlPlaneReasonCodes.add("PERSISTENCE_CONTROL_PLANE_ERROR");
        }
        validateSnapshotStoragePath(controlPlaneReasonCodes);
        Map<String, Integer> journalVersions = readMigrationJournal(controlPlaneReasonCodes);
        PersistenceBackendStatus backendStatus = backend.status();
        if (!"READY".equals(backendStatus.state())) {
            controlPlaneReasonCodes.add(backendStatus.reasonCode());
        }
        for (PersistenceArtifactDescriptor descriptor : catalog.artifacts()) {
            PersistenceArtifactStatus status = backendArtifactStatus(descriptor, backendStatus);
            if (status == null) {
                try {
                    status = integrityService.inspect(descriptor);
                } catch (RuntimeException exception) {
                    status = new PersistenceArtifactStatus(
                            descriptor.artifactId(),
                            PersistenceArtifactState.CORRUPTED,
                            descriptor.schemaVersion(),
                            null,
                            null,
                            null,
                            null,
                            Instant.now(),
                            "PERSISTENCE_CONTROL_PLANE_ERROR");
                    controlPlaneReasonCodes.add("PERSISTENCE_CONTROL_PLANE_ERROR");
                }
                status = applyMigrationState(descriptor, status, journalVersions, controlPlaneReasonCodes);
            }
            observed.add(new StatusWithCritical(descriptor.critical(), status));
        }
        return new StatusCalculation(List.copyOf(observed), List.copyOf(controlPlaneReasonCodes));
    }

    private PersistenceArtifactStatus backendArtifactStatus(PersistenceArtifactDescriptor descriptor,
                                                             PersistenceBackendStatus backendStatus) {
        if ("json".equals(descriptor.physicalBackend())) {
            return null;
        }
        if ("READY".equals(backendStatus.state())
                && descriptor.physicalBackend().equals(backendStatus.backendId())) {
            return new PersistenceArtifactStatus(
                    descriptor.artifactId(), PersistenceArtifactState.READY, descriptor.schemaVersion(),
                    descriptor.schemaVersion(), null, null, null, Instant.now(), "");
        }
        return new PersistenceArtifactStatus(
                descriptor.artifactId(), PersistenceArtifactState.CORRUPTED, descriptor.schemaVersion(), null,
                null, null, null, Instant.now(), backendStatus.reasonCode());
    }

    private void validateSnapshotStoragePath(Set<String> controlPlaneReasonCodes) {
        if (properties == null || configuredRoot == null) {
            return;
        }
        try {
            validateSafeDirectoryPath(properties.snapshotStoragePath(configuredRoot));
        } catch (RuntimeException exception) {
            controlPlaneReasonCodes.add("PERSISTENCE_CONTROL_PLANE_ERROR");
        }
    }

    private PersistenceArtifactStatus applyMigrationState(PersistenceArtifactDescriptor descriptor,
                                                           PersistenceArtifactStatus status,
                                                           Map<String, Integer> journalVersions,
                                                           Set<String> controlPlaneReasonCodes) {
        if (status.state() != PersistenceArtifactState.READY) {
            return status;
        }
        int observedVersion = journalVersions.getOrDefault(descriptor.artifactId(), 1);
        if (observedVersion > descriptor.schemaVersion()) {
            controlPlaneReasonCodes.add("PERSISTENCE_MIGRATION_UNSUPPORTED");
            return new PersistenceArtifactStatus(
                    status.artifactId(),
                    PersistenceArtifactState.CORRUPTED,
                    status.schemaVersion(),
                    observedVersion,
                    status.sizeBytes(),
                    status.sha256(),
                    status.recordCount(),
                    status.checkedAt(),
                    "PERSISTENCE_MIGRATION_UNSUPPORTED");
        }
        if (observedVersion < descriptor.schemaVersion()) {
            controlPlaneReasonCodes.add("PERSISTENCE_MIGRATION_REQUIRED");
            return new PersistenceArtifactStatus(
                    status.artifactId(),
                    PersistenceArtifactState.MIGRATION_REQUIRED,
                    status.schemaVersion(),
                    observedVersion,
                    status.sizeBytes(),
                    status.sha256(),
                    status.recordCount(),
                    status.checkedAt(),
                    "PERSISTENCE_MIGRATION_REQUIRED");
        }
        return new PersistenceArtifactStatus(
                status.artifactId(),
                status.state(),
                status.schemaVersion(),
                observedVersion,
                status.sizeBytes(),
                status.sha256(),
                status.recordCount(),
                status.checkedAt(),
                status.stableReasonCode());
    }

    private String overallState(List<StatusWithCritical> observed, List<String> controlPlaneReasonCodes) {
        if (!controlPlaneReasonCodes.isEmpty()) {
            return "FAIL_CLOSED";
        }
        boolean degraded = false;
        for (StatusWithCritical value : observed) {
            PersistenceArtifactStatus status = value.status();
            if (status.state() == PersistenceArtifactState.READY) {
                continue;
            }
            if (status.state() == PersistenceArtifactState.OPTIONAL_MISSING && !value.critical()) {
                degraded = true;
                continue;
            }
            return "FAIL_CLOSED";
        }
        return degraded ? "DEGRADED" : "READY";
    }

    private PersistenceArtifactStatusView statusView(StatusWithCritical value) {
        PersistenceArtifactStatus status = value.status();
        return new PersistenceArtifactStatusView(
                status.artifactId(),
                "PERSISTENCE_MIGRATION_UNSUPPORTED".equals(status.stableReasonCode())
                        ? "MIGRATION_UNSUPPORTED"
                        : status.state().name(),
                status.schemaVersion(),
                status.observedVersion(),
                status.sizeBytes(),
                status.sha256(),
                status.recordCount(),
                status.checkedAt(),
                status.stableReasonCode());
    }

    private Map<String, Integer> readMigrationJournal(Set<String> controlPlaneReasonCodes) {
        if (properties == null || configuredRoot == null) {
            return Map.of();
        }
        final Path journalPath;
        try {
            Path controlRoot = properties.controlStoragePath(configuredRoot);
            validateSafeDirectoryPath(controlRoot);
            journalPath = controlRoot.resolve("migration-journal.json").normalize();
            if (!journalPath.startsWith(controlRoot)) {
                throw new IllegalArgumentException("migration journal escaped control root");
            }
            validateSafePath(journalPath);
        } catch (RuntimeException exception) {
            controlPlaneReasonCodes.add("PERSISTENCE_CONTROL_PLANE_ERROR");
            return Map.of();
        }
        if (!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        if (Files.isSymbolicLink(journalPath)
                || !Files.isRegularFile(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            controlPlaneReasonCodes.add("PERSISTENCE_ARTIFACT_CORRUPTED");
            return Map.of();
        }
        try {
            PersistenceMigrationJournal.JournalRecord[] records = new ObjectMapper()
                    .findAndRegisterModules()
                    .readValue(journalPath.toFile(), PersistenceMigrationJournal.JournalRecord[].class);
            Map<String, Integer> versions = new HashMap<>();
            Set<String> knownArtifactIds = catalog.artifacts().stream()
                    .map(PersistenceArtifactDescriptor::artifactId)
                    .collect(java.util.stream.Collectors.toSet());
            for (PersistenceMigrationJournal.JournalRecord record : records == null
                    ? new PersistenceMigrationJournal.JournalRecord[0] : records) {
                if (record == null
                        || record.artifactId() == null
                        || record.artifactId().isBlank()
                        || !knownArtifactIds.contains(record.artifactId())
                        || record.fromVersion() < 1
                        || record.toVersion() < record.fromVersion()
                        || record.executedAt() == null
                        || record.result() == null
                        || !("SUCCESS".equals(record.result()) || "FAILED".equals(record.result()))) {
                    controlPlaneReasonCodes.add("PERSISTENCE_ARTIFACT_CORRUPTED");
                    return Map.of();
                }
                if ("SUCCESS".equals(record.result())) {
                    versions.merge(record.artifactId(), record.toVersion(), Math::max);
                }
            }
            return Map.copyOf(versions);
        } catch (IOException | RuntimeException exception) {
            controlPlaneReasonCodes.add("PERSISTENCE_ARTIFACT_CORRUPTED");
            return Map.of();
        }
    }

    private void validateSafePath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(configuredRoot)) {
            throw new IllegalArgumentException("path escaped configured root");
        }
        Path current = normalized;
        while (current != null && current.startsWith(configuredRoot)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("path contains a symbolic link");
            }
            if (current.equals(configuredRoot)) {
                return;
            }
            current = current.getParent();
        }
    }

    private void validateSafeDirectoryPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        validateSafePath(normalized);
        Path current = normalized;
        while (current != null && current.startsWith(configuredRoot)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(current))) {
                throw new IllegalArgumentException("path is not a safe directory");
            }
            if (current.equals(configuredRoot)) {
                return;
            }
            current = current.getParent();
        }
    }

    private boolean isFailClosedMode() {
        return properties == null
                || properties.getStartupMode() == null
                || properties.getStartupMode().isBlank()
                || "fail-closed".equalsIgnoreCase(properties.getStartupMode());
    }

    private List<String> startupFailureReasonCodes(PersistenceStatusView status) {
        LinkedHashSet<String> reasonCodes = new LinkedHashSet<>(status.controlPlaneReasonCodes());
        for (PersistenceArtifactStatusView artifact : status.artifacts()) {
            if (!artifact.stableReasonCode().isBlank()) {
                reasonCodes.add(artifact.stableReasonCode());
            }
        }
        return List.copyOf(reasonCodes);
    }

    private static Path configuredRootPath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("skill-center.persistence.configured-root must not be blank");
        }
        return Path.of(value.trim()).toAbsolutePath().normalize();
    }

    private PersistenceSnapshotView snapshotView(PersistenceSnapshotManifest manifest) {
        List<PersistenceSnapshotArtifactView> artifacts = manifest.artifacts().stream()
                .map(artifact -> new PersistenceSnapshotArtifactView(
                        artifact.artifactId(),
                        artifact.kind().name(),
                        artifact.schemaVersion(),
                        artifact.availability(),
                        artifact.relativePath(),
                        artifact.sizeBytes(),
                        artifact.sha256(),
                        artifact.recordCount()))
                .toList();
        return new PersistenceSnapshotView(
                manifest.snapshotId(),
                manifest.createdAt(),
                manifest.backend(),
                manifest.artifactCount(),
                manifest.manifestSha256(),
                manifest.state(),
                artifacts);
    }

    private record StatusWithCritical(boolean critical, PersistenceArtifactStatus status) {
    }

    private record StatusCalculation(List<StatusWithCritical> observed, List<String> controlPlaneReasonCodes) {
    }

    public record PersistenceStatusView(String overall,
                                        List<PersistenceArtifactStatusView> artifacts,
                                        List<String> controlPlaneReasonCodes) {
        public PersistenceStatusView(String overall, List<PersistenceArtifactStatusView> artifacts) {
            this(overall, artifacts, List.of());
        }

        public PersistenceStatusView {
            overall = requireText(overall, "overall");
            artifacts = List.copyOf(artifacts == null ? List.of() : artifacts);
            controlPlaneReasonCodes = List.copyOf(
                    controlPlaneReasonCodes == null ? List.of() : controlPlaneReasonCodes);
        }
    }

    public record PersistenceArtifactStatusView(
            String artifactId,
            String state,
            int schemaVersion,
            Integer observedVersion,
            Long sizeBytes,
            String sha256,
            Long recordCount,
            Instant checkedAt,
            String stableReasonCode) {
        public PersistenceArtifactStatusView {
            artifactId = requireText(artifactId, "artifactId");
            state = requireText(state, "state");
            stableReasonCode = stableReasonCode == null ? "" : stableReasonCode.trim();
        }
    }

    public record PersistenceSnapshotView(
            String snapshotId,
            Instant createdAt,
            String backend,
            int artifactCount,
            String manifestSha256,
            String state,
            List<PersistenceSnapshotArtifactView> artifacts) {
        public PersistenceSnapshotView {
            snapshotId = requireText(snapshotId, "snapshotId");
            backend = requireText(backend, "backend");
            state = requireText(state, "state");
            artifacts = List.copyOf(artifacts == null ? List.of() : artifacts);
        }
    }

    public record PersistenceSnapshotArtifactView(
            String artifactId,
            String kind,
            int schemaVersion,
            String availability,
            String relativePath,
            Long sizeBytes,
            String sha256,
            Long recordCount) {
        public PersistenceSnapshotArtifactView {
            artifactId = requireText(artifactId, "artifactId");
            kind = requireText(kind, "kind");
            availability = requireText(availability, "availability");
            relativePath = relativePath == null ? "" : relativePath.trim();
        }
    }

    public record PersistencePreflightView(
            String snapshotId,
            String status,
            String reasonCode,
            int artifactCount) {
        public PersistencePreflightView {
            snapshotId = requireText(snapshotId, "snapshotId");
            status = requireText(status, "status");
            reasonCode = reasonCode == null ? "" : reasonCode.trim();
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
