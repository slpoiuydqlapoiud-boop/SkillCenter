package com.huawei.skillcenter.analytics;

import com.huawei.skillcenter.events.InvocationEvent;
import com.huawei.skillcenter.events.InvocationIngestionStats;
import com.huawei.skillcenter.governance.InstallationRecord;
import com.huawei.skillcenter.skill.SkillSummary;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class AnalyticsAggregator {
    public AnalyticsOverview aggregate(AnalyticsQuery query,
                                       List<SkillSummary> skills,
                                       Collection<InvocationEvent> events,
                                       List<InstallationRecord> installations,
                                       InvocationIngestionStats ingestionStats) {
        List<SkillSummary> catalog = skills == null ? List.of() : skills;
        List<InvocationEvent> filteredEvents = (events == null ? List.<InvocationEvent>of() : events).stream()
                .filter(Objects::nonNull)
                .filter(event -> withinRange(event.occurredAt(), query))
                .filter(event -> matchesEventDimensions(event, query))
                .toList();
        List<InstallationRecord> records = installations == null ? List.of() : installations;
        List<InstallationRecord> matchingInstallations = records.stream()
                .filter(Objects::nonNull)
                .filter(record -> matchesInstallationDimensions(record, query))
                .toList();

        long calls = filteredEvents.size();
        long successes = filteredEvents.stream().filter(event -> "success".equals(event.status())).count();
        long activeSkills = filteredEvents.stream().map(InvocationEvent::skillId).filter(Objects::nonNull).distinct().count();
        long activeUsers = filteredEvents.stream().map(InvocationEvent::subject)
                .filter(Objects::nonNull).map(InvocationEvent.Subject::userId)
                .filter(value -> value != null && !value.isBlank()).distinct().count();
        long activeTeams = filteredEvents.stream().map(InvocationEvent::subject)
                .filter(Objects::nonNull).map(InvocationEvent.Subject::teamId)
                .filter(value -> value != null && !value.isBlank()).distinct().count();
        long currentInstallations = matchingInstallations.stream()
                .filter(record -> "installed".equals(record.status()) || "installing".equals(record.status()))
                .count();
        List<InstallationRecord> requestsInRange = matchingInstallations.stream()
                .filter(record -> withinRange(record.requestedAt(), query))
                .toList();
        long successfulInstallations = requestsInRange.stream()
                .filter(record -> "installed".equals(record.status())).count();

        return new AnalyticsOverview(
                new AnalyticsOverview.Kpis(calls, successes, percentage(successes, calls), activeSkills,
                        activeUsers, activeTeams, currentInstallations,
                        percentage(successfulInstallations, requestsInRange.size())),
                series(query, filteredEvents, requestsInRange),
                topSkills(catalog, filteredEvents),
                versionAdoption(filteredEvents, calls),
                errorBreakdown(filteredEvents),
                latency(filteredEvents),
                quality(query, ingestionStats));
    }

    private List<AnalyticsOverview.DayPoint> series(AnalyticsQuery query,
                                                    List<InvocationEvent> events,
                                                    List<InstallationRecord> installations) {
        Map<LocalDate, List<InvocationEvent>> eventsByDay = events.stream()
                .collect(Collectors.groupingBy(event -> dayOf(event.occurredAt(), query.zoneId())));
        Map<LocalDate, Long> installationsByDay = installations.stream()
                .filter(record -> record.requestedAt() != null)
                .collect(Collectors.groupingBy(record -> dayOf(record.requestedAt(), query.zoneId()), Collectors.counting()));
        List<AnalyticsOverview.DayPoint> result = new ArrayList<>();
        for (LocalDate day = query.from(); !day.isAfter(query.to()); day = day.plusDays(1)) {
            List<InvocationEvent> dayEvents = eventsByDay.getOrDefault(day, List.of());
            long calls = dayEvents.size();
            long successes = dayEvents.stream().filter(event -> "success".equals(event.status())).count();
            long users = dayEvents.stream().map(InvocationEvent::subject).filter(Objects::nonNull)
                    .map(InvocationEvent.Subject::userId).filter(value -> value != null && !value.isBlank()).distinct().count();
            result.add(new AnalyticsOverview.DayPoint(day.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd")),
                    calls, percentage(successes, calls), users, installationsByDay.getOrDefault(day, 0L)));
        }
        return result;
    }

    private List<AnalyticsOverview.TopSkill> topSkills(List<SkillSummary> skills, List<InvocationEvent> events) {
        Map<String, Long> callsBySkill = events.stream().collect(Collectors.groupingBy(InvocationEvent::skillId, Collectors.counting()));
        Map<String, Long> successesBySkill = events.stream().filter(event -> "success".equals(event.status()))
                .collect(Collectors.groupingBy(InvocationEvent::skillId, Collectors.counting()));
        return skills.stream().map(skill -> {
                    long calls = callsBySkill.getOrDefault(skill.id(), 0L);
                    return new AnalyticsOverview.TopSkill(skill.id(), skill.name(), calls,
                            percentage(successesBySkill.getOrDefault(skill.id(), 0L), calls));
                }).sorted(Comparator.comparingLong(AnalyticsOverview.TopSkill::calls).reversed()
                        .thenComparing(AnalyticsOverview.TopSkill::id))
                .limit(5)
                .toList();
    }

    private List<AnalyticsOverview.VersionAdoption> versionAdoption(List<InvocationEvent> events, long totalCalls) {
        Map<String, Long> counts = new HashMap<>();
        events.forEach(event -> counts.merge(event.skillId() + "\u0000" + event.version(), 1L, Long::sum));
        return counts.entrySet().stream()
                .map(entry -> {
                    String[] parts = entry.getKey().split("\u0000", 2);
                    return new AnalyticsOverview.VersionAdoption(parts[0], parts[1], entry.getValue(),
                            percentage(entry.getValue(), totalCalls));
                })
                .sorted(Comparator.comparingLong(AnalyticsOverview.VersionAdoption::calls).reversed()
                        .thenComparing(AnalyticsOverview.VersionAdoption::skillId)
                        .thenComparing(AnalyticsOverview.VersionAdoption::version))
                .toList();
    }

    private List<AnalyticsOverview.ErrorBreakdown> errorBreakdown(List<InvocationEvent> events) {
        Map<String, Long> counts = events.stream()
                .filter(event -> "failure".equals(event.status()) || "timeout".equals(event.status()))
                .map(InvocationEvent::errorCode)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return counts.entrySet().stream()
                .map(entry -> new AnalyticsOverview.ErrorBreakdown(entry.getKey(), entry.getValue(),
                        percentage(entry.getValue(), total)))
                .sorted(Comparator.comparingLong(AnalyticsOverview.ErrorBreakdown::count).reversed()
                        .thenComparing(AnalyticsOverview.ErrorBreakdown::errorCode))
                .toList();
    }

    private AnalyticsOverview.Latency latency(List<InvocationEvent> events) {
        List<Long> values = events.stream().map(InvocationEvent::durationMs).sorted().toList();
        if (values.isEmpty()) {
            return AnalyticsOverview.Latency.empty();
        }
        return new AnalyticsOverview.Latency(values.size(), percentile(values, 0.50), percentile(values, 0.95), values.get(values.size() - 1));
    }

    private AnalyticsOverview.DataQuality quality(AnalyticsQuery query, InvocationIngestionStats stats) {
        if (stats == null || stats.entries().isEmpty()) {
            return AnalyticsOverview.DataQuality.empty();
        }
        List<InvocationIngestionStats.Entry> entries = stats.entries().stream()
                .filter(entry -> entry.occurredAt() == null || withinRange(entry.occurredAt(), query))
                .toList();
        long accepted = entries.stream().filter(entry -> entry.result() == InvocationIngestionStats.Result.ACCEPTED).count();
        long duplicates = entries.stream().filter(entry -> entry.result() == InvocationIngestionStats.Result.DUPLICATE).count();
        long rejected = entries.stream().filter(entry -> entry.result() == InvocationIngestionStats.Result.REJECTED).count();
        List<OffsetDateTime> occurred = entries.stream().map(InvocationIngestionStats.Entry::occurredAt)
                .filter(Objects::nonNull).sorted().toList();
        boolean backfill = entries.stream().anyMatch(entry -> entry.occurredAt() != null
                && entry.occurredAt().toInstant().isBefore(entry.receivedAt())
                && dayOf(entry.occurredAt(), query.zoneId()).isBefore(LocalDate.now(query.zoneId())));
        return new AnalyticsOverview.DataQuality(accepted, duplicates, rejected,
                occurred.isEmpty() ? null : occurred.get(0).toString(),
                occurred.isEmpty() ? null : occurred.get(occurred.size() - 1).toString(), backfill);
    }

    private boolean matchesEventDimensions(InvocationEvent event, AnalyticsQuery query) {
        return matches(query.skillId(), event.skillId())
                && matches(query.teamId(), event.subject() == null ? null : event.subject().teamId())
                && matches(query.clientType(), event.client() == null ? null : event.client().type());
    }

    private boolean matchesInstallationDimensions(InstallationRecord record, AnalyticsQuery query) {
        return matches(query.skillId(), record.skillId())
                && matches(query.teamId(), record.teamId())
                && matches(query.clientType(), record.clientType());
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }

    private boolean withinRange(OffsetDateTime occurredAt, AnalyticsQuery query) {
        return occurredAt != null && withinRange(occurredAt.toInstant(), query);
    }

    private boolean withinRange(Instant timestamp, AnalyticsQuery query) {
        if (timestamp == null) {
            return false;
        }
        LocalDate day = timestamp.atZone(query.zoneId()).toLocalDate();
        return !day.isBefore(query.from()) && !day.isAfter(query.to());
    }

    private LocalDate dayOf(OffsetDateTime timestamp, ZoneId zoneId) {
        return timestamp.toInstant().atZone(zoneId).toLocalDate();
    }

    private LocalDate dayOf(Instant timestamp, ZoneId zoneId) {
        return timestamp.atZone(zoneId).toLocalDate();
    }

    private long percentile(List<Long> values, double percentile) {
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private double percentage(long numerator, long denominator) {
        return denominator == 0 ? 0 : Math.round((numerator * 10000.0 / denominator)) / 100.0;
    }
}
