package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.events.InvocationEvent;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuditProjectionService {
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> SAFE_METADATA_KEYS = Set.of("dataset", "format", "rowCount", "policyVersion");

    public List<AuditSummaryRow> auditSummary(Collection<AuditEvent> events) {
        return (events == null ? List.<AuditEvent>of() : events).stream()
                .filter(Objects::nonNull)
                .map(event -> new AuditSummaryRow(event.auditId(), event.action(), event.resourceType(),
                        event.resourceId(), event.actorRole(), event.requestId(), event.occurredAt(),
                        safeMetadata(event.metadata())))
                .toList();
    }

    public List<InvocationSummaryRow> invocationSummary(Collection<InvocationEvent> events, ExportFilters filters) {
        ExportFilters safeFilters = filters == null ? ExportFilters.empty() : filters;
        Map<InvocationKey, List<InvocationEvent>> grouped = (events == null ? List.<InvocationEvent>of() : events).stream()
                .filter(Objects::nonNull)
                .filter(event -> matches(event, safeFilters))
                .collect(Collectors.groupingBy(this::invocationKey, LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(InvocationKey::date)
                        .thenComparing(InvocationKey::skillId)
                        .thenComparing(InvocationKey::version)
                        .thenComparing(InvocationKey::status)
                        .thenComparing(InvocationKey::clientType)
                        .thenComparing(InvocationKey::clientVersion)))
                .map(entry -> {
                    List<InvocationEvent> rows = entry.getValue();
                    List<Long> durations = rows.stream().map(InvocationEvent::durationMs).sorted().toList();
                    long errors = rows.stream().filter(row -> "failure".equals(row.status()) || "timeout".equals(row.status())).count();
                    long average = Math.round(rows.stream().mapToLong(InvocationEvent::durationMs).average().orElse(0));
                    return new InvocationSummaryRow(entry.getKey().date(), entry.getKey().skillId(), entry.getKey().version(),
                            entry.getKey().status(), entry.getKey().clientType(), entry.getKey().clientVersion(),
                            rows.size(), average, percentile(durations, 0.95), errors);
                }).toList();
    }

    public List<InstallationSummaryRow> installationSummary(Collection<InstallationRecord> records, ExportFilters filters) {
        ExportFilters safeFilters = filters == null ? ExportFilters.empty() : filters;
        return (records == null ? List.<InstallationRecord>of() : records).stream()
                .filter(Objects::nonNull)
                .filter(record -> matches(record, safeFilters))
                .collect(Collectors.groupingBy(record -> new InstallationKey(dayOf(record.requestedAt()), record.skillId(),
                        record.version(), record.status(), record.clientType(), record.clientVersion()), LinkedHashMap::new,
                        Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(InstallationKey::date)
                        .thenComparing(InstallationKey::skillId)
                        .thenComparing(InstallationKey::version)
                        .thenComparing(InstallationKey::status)
                        .thenComparing(InstallationKey::clientType)
                        .thenComparing(InstallationKey::clientVersion)))
                .map(entry -> new InstallationSummaryRow(entry.getKey().date(), entry.getKey().skillId(), entry.getKey().version(),
                        entry.getKey().status(), entry.getKey().clientType(), entry.getKey().clientVersion(), entry.getValue()))
                .toList();
    }

    private Map<String, String> safeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        return metadata.entrySet().stream()
                .filter(entry -> SAFE_METADATA_KEYS.contains(entry.getKey()))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private boolean matches(InvocationEvent event, ExportFilters filters) {
        return within(event.occurredAt() == null ? null : event.occurredAt().toInstant(), filters)
                && matches(filters.skillId(), event.skillId())
                && matches(filters.teamId(), event.subject() == null ? null : event.subject().teamId())
                && matches(filters.clientType(), event.client() == null ? null : event.client().type())
                && matches(filters.status(), event.status());
    }

    private boolean matches(InstallationRecord record, ExportFilters filters) {
        return within(record.requestedAt(), filters)
                && matches(filters.skillId(), record.skillId())
                && matches(filters.teamId(), record.teamId())
                && matches(filters.clientType(), record.clientType())
                && matches(filters.status(), record.status());
    }

    private boolean within(Instant timestamp, ExportFilters filters) {
        if (timestamp == null) {
            return false;
        }
        return (filters.from() == null || !timestamp.isBefore(filters.from().toInstant()))
                && (filters.to() == null || !timestamp.isAfter(filters.to().toInstant()));
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }

    private InvocationKey invocationKey(InvocationEvent event) {
        return new InvocationKey(dayOf(event.occurredAt() == null ? null : event.occurredAt().toInstant()),
                event.skillId(), event.version(), event.status(),
                event.client() == null ? null : event.client().type(),
                event.client() == null ? null : event.client().version());
    }

    private LocalDate dayOf(Instant timestamp) {
        return timestamp == null ? null : timestamp.atZone(REPORTING_ZONE).toLocalDate();
    }

    private long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private record InvocationKey(LocalDate date, String skillId, String version, String status,
                                 String clientType, String clientVersion) {}

    private record InstallationKey(LocalDate date, String skillId, String version, String status,
                                    String clientType, String clientVersion) {}
}
