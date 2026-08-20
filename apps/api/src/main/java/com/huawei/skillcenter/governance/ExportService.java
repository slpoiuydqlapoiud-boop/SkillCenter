package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.events.InvocationEventStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

@Service
public class ExportService {
    private static final int MAX_CONCURRENCY = 2;
    private static final int MAX_QUEUE = 20;
    private static final int DOWNLOAD_TTL_MINUTES = 15;
    private static final List<String> AUDIT_REDACTIONS = List.of("actorId", "unsafeMetadata");
    private static final List<String> INVOCATION_REDACTIONS = List.of("userId", "teamId", "sessionId", "usage", "model", "tokens");
    private static final List<String> INSTALLATION_REDACTIONS = List.of("requestedBy", "deviceId", "method", "filePath", "credentials");

    private final GovernanceStore store;
    private final AuditProjectionService projectionService;
    private final InvocationEventStore invocationEventStore;
    private final Path artifactRoot;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(MAX_CONCURRENCY, MAX_CONCURRENCY,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_QUEUE));

    @Autowired
    public ExportService(GovernanceStore store,
                         AuditProjectionService projectionService,
                         InvocationEventStore invocationEventStore,
                         @Value("${skill-center.export-storage:./data/governance/exports}") String artifactRoot) {
        this(store, projectionService, invocationEventStore, Path.of(artifactRoot));
    }

    public ExportService(GovernanceStore store,
                         AuditProjectionService projectionService,
                         InvocationEventStore invocationEventStore,
                         Path artifactRoot) {
        this.store = store;
        this.projectionService = projectionService;
        this.invocationEventStore = invocationEventStore;
        this.artifactRoot = artifactRoot.toAbsolutePath().normalize();
    }

    public ExportJob create(ExportRequest request, Actor actor, String requestId) {
        requireExportRole(actor);
        if (request == null || request.dataset() == null || request.format() == null) {
            throw new ExportException("EXPORT_FILTER_INVALID", "Export request is invalid");
        }
        if (executor.getQueue().remainingCapacity() == 0) {
            throw new ExportException("EXPORT_QUEUE_FULL", "Export queue is full");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ExportJob job = new ExportJob(UUID.randomUUID(), request.dataset(), request.format(), request.filters(),
                actor.userId(), actor.role(), requestIdOrDefault(requestId), ExportJobStatus.QUEUED, now,
                null, null, now.plusMinutes(DOWNLOAD_TTL_MINUTES), null, 0, null, redactions(request.dataset()),
                null, null, null, null, null);
        store.addExportJob(job);
        addAudit("EXPORT_CREATED", job, actor, requestId, java.util.Map.of(
                "dataset", job.dataset().name(), "format", job.format().name()));
        try {
            executor.execute(() -> run(job.jobId()));
        } catch (RejectedExecutionException exception) {
            store.updateExportJob(job.failed("EXPORT_QUEUE_FULL", "Export queue is full", OffsetDateTime.now(ZoneOffset.UTC)));
            throw new ExportException("EXPORT_QUEUE_FULL", "Export queue is full");
        }
        return job;
    }

    public ExportJob awaitCompletion(UUID jobId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            ExportJob job = find(jobId);
            if (job.status() != ExportJobStatus.QUEUED && job.status() != ExportJobStatus.RUNNING) {
                return job;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ExportException("EXPORT_INTERRUPTED", "Export wait was interrupted");
            }
        }
        throw new ExportException("EXPORT_FAILED", "Export did not complete in time");
    }

    public List<ExportJob> list(Actor actor, String status, String dataset) {
        requireExportRole(actor);
        ExportJobStatus requestedStatus = parseStatus(status);
        ExportDataset requestedDataset = parseDataset(dataset);
        return store.snapshot().exportJobs().stream()
                .filter(job -> "admin".equals(actor.role()) || actor.userId().equals(job.requestedBy()))
                .filter(job -> requestedStatus == null || requestedStatus == job.status())
                .filter(job -> requestedDataset == null || requestedDataset == job.dataset())
                .sorted(java.util.Comparator.comparing(ExportJob::createdAt).reversed())
                .toList();
    }

    public ExportJob retry(UUID jobId, Actor actor, String requestId) {
        if (actor == null || !"admin".equals(actor.role())) {
            throw new ExportException("EXPORT_FORBIDDEN", "Only admin can retry exports");
        }
        ExportJob job = find(jobId);
        if (job.status() != ExportJobStatus.FAILED) {
            throw new ExportException("EXPORT_NOT_READY", "Only failed exports can be retried");
        }
        ExportJob queued = job.transitionTo(ExportJobStatus.QUEUED, OffsetDateTime.now(ZoneOffset.UTC));
        store.updateExportJob(queued);
        addAudit("EXPORT_RETRY", queued, actor, requestId, java.util.Map.of());
        try {
            executor.execute(() -> run(queued.jobId()));
        } catch (RejectedExecutionException exception) {
            ExportJob failed = queued.failed("EXPORT_QUEUE_FULL", "Export queue is full", OffsetDateTime.now(ZoneOffset.UTC));
            store.updateExportJob(failed);
            throw new ExportException("EXPORT_QUEUE_FULL", "Export queue is full");
        }
        return queued;
    }

    public DownloadUrlResponse issueDownloadUrl(UUID jobId, Actor actor, String requestId) {
        ExportJob job = accessibleJob(jobId, actor);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        requireReady(job, now);
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        OffsetDateTime expiresAt = now.plusMinutes(DOWNLOAD_TTL_MINUTES);
        store.updateExportJob(job.withDownloadToken(hash(token), expiresAt));
        addAudit("EXPORT_DOWNLOAD_URL_ISSUED", job, actor, requestId, java.util.Map.of());
        return new DownloadUrlResponse("/api/v1/admin/exports/" + jobId + "/download?token=" + token,
                token, expiresAt);
    }

    public ExportDownload download(UUID jobId, String token, Actor actor, String requestId) {
        ExportJob job = accessibleJob(jobId, actor);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        requireReady(job, now);
        if (token == null || token.isBlank() || job.downloadTokenHash() == null
                || !job.downloadTokenHash().equals(hash(token))
                || job.downloadTokenExpiresAt() == null || !now.isBefore(job.downloadTokenExpiresAt())
                || job.downloadTokenConsumedAt() != null) {
            addAudit("EXPORT_DOWNLOAD", job, actor, requestId, java.util.Map.of("result", "denied"));
            throw new ExportException("EXPORT_EXPIRED", "Download token is expired or invalid");
        }
        Path path = resolveArtifact(job);
        try {
            byte[] content = Files.readAllBytes(path);
            ExportJob consumed = job.consumeDownload(now);
            store.updateExportJob(consumed);
            addAudit("EXPORT_DOWNLOAD", consumed, actor, requestId, java.util.Map.of("result", "success"));
            return new ExportDownload(content, fileName(job), contentType(job.format()), job.sha256());
        } catch (IOException exception) {
            throw new ExportException("EXPORT_FAILED", "Export artifact is unavailable");
        }
    }

    public ExportJob detail(UUID jobId, Actor actor) {
        return accessibleJob(jobId, actor);
    }

    private void run(UUID jobId) {
        ExportJob job = find(jobId);
        try {
            ExportJob running = job.transitionTo(ExportJobStatus.RUNNING, OffsetDateTime.now(ZoneOffset.UTC));
            store.updateExportJob(running);
            List<?> rows = switch (running.dataset()) {
                case AUDIT_SUMMARY -> projectionService.auditSummary(store.snapshot().audits());
                case INVOCATION_SUMMARY -> projectionService.invocationSummary(invocationEventStore.events(), running.filters());
                case INSTALLATION_SUMMARY -> projectionService.installationSummary(store.snapshot().installations(), running.filters());
            };
            Files.createDirectories(artifactRoot);
            Path root = artifactRoot.toAbsolutePath().normalize();
            Path path = root.resolve(running.jobId() + "." + running.format().name().toLowerCase(java.util.Locale.ROOT)).normalize();
            if (!path.startsWith(root)) {
                throw new ExportException("EXPORT_FAILED", "Export path is invalid");
            }
            byte[] content = running.format() == ExportFormat.JSON ? json(rows) : csv(running.dataset(), rows);
            Files.write(path, content);
            String relativePath = root.relativize(path).toString();
            ExportJob completed = running.completed(relativePath, rows.size(), hash(content), redactions(running.dataset()),
                    OffsetDateTime.now(ZoneOffset.UTC));
            store.updateExportJob(completed);
            addAudit("EXPORT_COMPLETED", completed,
                    new Actor(completed.requestedBy(), completed.requestedRole()), completed.requestId(),
                    java.util.Map.of("dataset", completed.dataset().name(), "rowCount", String.valueOf(completed.rowCount())));
        } catch (Exception exception) {
            ExportJob current = find(jobId);
            if (current.status() == ExportJobStatus.RUNNING || current.status() == ExportJobStatus.QUEUED) {
                ExportJob failed = current.failed(exception instanceof ExportException exportException
                                ? exportException.code() : "EXPORT_FAILED",
                        "Export could not be completed", OffsetDateTime.now(ZoneOffset.UTC));
                store.updateExportJob(failed);
                addAudit("EXPORT_FAILED", failed,
                        new Actor(failed.requestedBy(), failed.requestedRole()), failed.requestId(),
                        java.util.Map.of("result", "failed"));
            }
        }
    }

    @PostConstruct
    void recoverPersistedJobs() {
        for (ExportJob job : store.snapshot().exportJobs()) {
            if (job.status() == ExportJobStatus.RUNNING) {
                store.updateExportJob(job.failed("EXPORT_INTERRUPTED", "Export was interrupted by a restart",
                        OffsetDateTime.now(ZoneOffset.UTC)));
            } else if (job.status() == ExportJobStatus.QUEUED) {
                try {
                    executor.execute(() -> run(job.jobId()));
                } catch (RejectedExecutionException exception) {
                    store.updateExportJob(job.failed("EXPORT_QUEUE_FULL", "Export queue is full",
                            OffsetDateTime.now(ZoneOffset.UTC)));
                }
            }
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private byte[] json(List<?> rows) {
        try {
            return objectMapper.writeValueAsBytes(rows);
        } catch (JsonProcessingException exception) {
            throw new ExportException("EXPORT_FAILED", "Unable to serialize export");
        }
    }

    private byte[] csv(ExportDataset dataset, List<?> rows) {
        StringBuilder csv = new StringBuilder();
        switch (dataset) {
            case AUDIT_SUMMARY -> {
                csv.append("auditId,action,resourceType,resourceId,actorRole,requestId,occurredAt,metadata\n");
                for (Object value : rows) {
                    AuditSummaryRow row = (AuditSummaryRow) value;
                    csv.append(line(row.auditId(), row.action(), row.resourceType(), row.resourceId(), row.actorRole(),
                            row.requestId(), row.occurredAt(), row.metadata())).append('\n');
                }
            }
            case INVOCATION_SUMMARY -> {
                csv.append("date,skillId,version,status,clientType,clientVersion,count,avgDurationMs,p95DurationMs,errorCount\n");
                for (Object value : rows) {
                    InvocationSummaryRow row = (InvocationSummaryRow) value;
                    csv.append(line(row.date(), row.skillId(), row.version(), row.status(), row.clientType(), row.clientVersion(),
                            row.count(), row.avgDurationMs(), row.p95DurationMs(), row.errorCount())).append('\n');
                }
            }
            case INSTALLATION_SUMMARY -> {
                csv.append("date,skillId,version,status,clientType,clientVersion,count\n");
                for (Object value : rows) {
                    InstallationSummaryRow row = (InstallationSummaryRow) value;
                    csv.append(line(row.date(), row.skillId(), row.version(), row.status(), row.clientType(), row.clientVersion(),
                            row.count())).append('\n');
                }
            }
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String line(Object... values) {
        List<String> escaped = new ArrayList<>();
        for (Object value : values) {
            String text = String.valueOf(value == null ? "" : value);
            escaped.add('"' + text.replace("\"", "\"\"") + '"');
        }
        return String.join(",", escaped);
    }

    private ExportJob accessibleJob(UUID jobId, Actor actor) {
        ExportJob job = find(jobId);
        if (!"admin".equals(actor.role()) && !("reviewer".equals(actor.role())
                && actor.userId().equals(job.requestedBy()))) {
            throw new ExportException("EXPORT_FORBIDDEN", "Actor does not have permission for this export");
        }
        return job;
    }

    private ExportJob find(UUID jobId) {
        return store.snapshot().exportJobs().stream()
                .filter(job -> job.jobId().equals(jobId))
                .findFirst()
                .orElseThrow(() -> new ExportException("EXPORT_NOT_FOUND", "Export job not found"));
    }

    private void requireReady(ExportJob job, OffsetDateTime now) {
        if (job.status() == ExportJobStatus.EXPIRED || !now.isBefore(job.expiresAt())) {
            throw new ExportException("EXPORT_EXPIRED", "Export has expired");
        }
        if (job.status() != ExportJobStatus.COMPLETED) {
            throw new ExportException("EXPORT_NOT_READY", "Export is not ready");
        }
    }

    private void requireExportRole(Actor actor) {
        if (actor == null || !("admin".equals(actor.role()) || "reviewer".equals(actor.role()))) {
            throw new ExportException("EXPORT_FORBIDDEN", "Actor does not have permission to create exports");
        }
    }

    private ExportJobStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ExportJobStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ExportException("EXPORT_FILTER_INVALID", "Export status filter is invalid");
        }
    }

    private ExportDataset parseDataset(String dataset) {
        if (dataset == null || dataset.isBlank()) {
            return null;
        }
        try {
            return ExportDataset.valueOf(dataset.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ExportException("EXPORT_FILTER_INVALID", "Export dataset filter is invalid");
        }
    }

    private Path resolveArtifact(ExportJob job) {
        if (job.artifactPath() == null || job.artifactPath().isBlank()) {
            throw new ExportException("EXPORT_NOT_READY", "Export artifact is not ready");
        }
        Path root = artifactRoot.toAbsolutePath().normalize();
        Path path = root.resolve(job.artifactPath()).normalize();
        if (!path.startsWith(root)) {
            throw new ExportException("EXPORT_FAILED", "Export artifact path is invalid");
        }
        return path;
    }

    private List<String> redactions(ExportDataset dataset) {
        return switch (dataset) {
            case AUDIT_SUMMARY -> AUDIT_REDACTIONS;
            case INVOCATION_SUMMARY -> INVOCATION_REDACTIONS;
            case INSTALLATION_SUMMARY -> INSTALLATION_REDACTIONS;
        };
    }

    private String fileName(ExportJob job) {
        return "skillcenter-" + job.dataset().name().toLowerCase(java.util.Locale.ROOT) + "-" + job.jobId()
                + "." + job.format().name().toLowerCase(java.util.Locale.ROOT);
    }

    private String contentType(ExportFormat format) {
        return format == ExportFormat.JSON ? "application/json" : "text/csv";
    }

    private String requestIdOrDefault(String requestId) {
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }

    private void addAudit(String action, ExportJob job, Actor actor, String requestId, java.util.Map<String, String> metadata) {
        store.addAudit(new AuditEvent(UUID.randomUUID().toString(), action, "EXPORT", job.jobId().toString(),
                actor == null ? job.requestedBy() : actor.userId(), actor == null ? job.requestedRole() : actor.role(),
                requestIdOrDefault(requestId), java.time.Instant.now(), metadata));
    }

    private String hash(String value) {
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }

    private String hash(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
