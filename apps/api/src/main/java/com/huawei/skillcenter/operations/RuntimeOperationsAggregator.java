package com.huawei.skillcenter.operations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class RuntimeOperationsAggregator {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter BUCKET_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZONE);
    private final Clock clock;

    public RuntimeOperationsAggregator() {
        this(Clock.systemUTC());
    }

    public RuntimeOperationsAggregator(Clock clock) {
        this.clock = clock;
    }

    public RuntimeOperationsSnapshot aggregate(RuntimeOperationsQuery query, List<RuntimeSummary> input) {
        RuntimeOperationsQuery effective = query == null ? new RuntimeOperationsQuery(null, null, null, null, null)
                : query;
        Instant now = effective.now() == null ? clock.instant() : effective.now();
        Instant from = now.minusSeconds(effective.window().seconds());
        List<RuntimeSummary> events = (input == null ? List.<RuntimeSummary>of() : input).stream()
                .filter(Objects::nonNull)
                .filter(event -> !event.occurredAt().toInstant().isBefore(from)
                        && !event.occurredAt().toInstant().isAfter(now))
                .filter(event -> matches(effective.skillId(), event.skillId()))
                .filter(event -> matches(effective.version(), event.version()))
                .filter(event -> matches(effective.teamId(), event.teamId()))
                .filter(event -> matches(effective.runtimeId(), event.runtimeId()))
                .filter(event -> matches(effective.mcpServerId(), event.mcpServerId()))
                .filter(event -> matches(effective.llmProviderId(), event.llmProviderId()))
                .filter(event -> effective.dataSource() == null || "all".equals(effective.dataSource())
                        || effective.dataSource().equals(event.dataSource()))
                .toList();
        return new RuntimeOperationsSnapshot(effective.window().label(), now,
                effective.dataSource() == null ? "all" : effective.dataSource(),
                new RuntimeOperationsSnapshot.Filters(effective.skillId(), effective.version(), effective.teamId(),
                        effective.runtimeId(), effective.mcpServerId(), effective.llmProviderId()),
                totals(events), latency(events), errors(events), adoption(events), sources(events), trend(events, effective.window(), from, now));
    }

    private RuntimeOperationsSnapshot.Totals totals(List<RuntimeSummary> events) {
        long successes = count(events, "success");
        long failures = count(events, "failure");
        long timeouts = count(events, "timeout");
        long cancellations = events.stream().filter(event -> "cancelled".equals(event.status())).count();
        return new RuntimeOperationsSnapshot.Totals(events.size(), successes, failures, timeouts, cancellations,
                percentage(successes, events.size()));
    }

    private RuntimeOperationsSnapshot.Latency latency(List<RuntimeSummary> events) {
        List<Long> values = events.stream().map(RuntimeSummary::durationMs).sorted().toList();
        if (values.isEmpty()) {
            return RuntimeOperationsSnapshot.Latency.empty();
        }
        return new RuntimeOperationsSnapshot.Latency(values.size(), percentile(values, .50),
                percentile(values, .95), values.get(values.size() - 1));
    }

    private List<RuntimeOperationsSnapshot.ErrorCount> errors(List<RuntimeSummary> events) {
        Map<String, Long> counts = events.stream()
                .filter(event -> "failure".equals(event.status()) || "timeout".equals(event.status()))
                .map(RuntimeSummary::errorCode).filter(value -> value != null && !value.isBlank())
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return counts.entrySet().stream()
                .map(entry -> new RuntimeOperationsSnapshot.ErrorCount(entry.getKey(), entry.getValue(),
                        percentage(entry.getValue(), total)))
                .sorted(Comparator.comparingLong(RuntimeOperationsSnapshot.ErrorCount::count).reversed()
                        .thenComparing(RuntimeOperationsSnapshot.ErrorCount::errorCode))
                .toList();
    }

    private List<RuntimeOperationsSnapshot.VersionAdoption> adoption(List<RuntimeSummary> events) {
        Map<String, Long> counts = events.stream().collect(Collectors.groupingBy(RuntimeSummary::version, Collectors.counting()));
        return counts.entrySet().stream()
                .map(entry -> new RuntimeOperationsSnapshot.VersionAdoption(entry.getKey(), entry.getValue(),
                        percentage(entry.getValue(), events.size())))
                .sorted(Comparator.comparingLong(RuntimeOperationsSnapshot.VersionAdoption::calls).reversed()
                        .thenComparing(RuntimeOperationsSnapshot.VersionAdoption::version))
                .toList();
    }

    private List<RuntimeOperationsSnapshot.SourceBreakdown> sources(List<RuntimeSummary> events) {
        return events.stream().collect(Collectors.groupingBy(RuntimeSummary::dataSource)).entrySet().stream()
                .map(entry -> {
                    List<RuntimeSummary> sourceEvents = entry.getValue();
                    return new RuntimeOperationsSnapshot.SourceBreakdown(entry.getKey(), sourceEvents.size(),
                            count(sourceEvents, "success"), count(sourceEvents, "failure"), count(sourceEvents, "timeout"),
                            percentage(count(sourceEvents, "success"), sourceEvents.size()), latency(sourceEvents).p95Ms());
                })
                .sorted(Comparator.comparing(RuntimeOperationsSnapshot.SourceBreakdown::dataSource))
                .toList();
    }

    private List<RuntimeOperationsSnapshot.TrendPoint> trend(List<RuntimeSummary> events,
                                                              RuntimeOperationsWindow window,
                                                              Instant from, Instant now) {
        long bucketSeconds = window.seconds() <= 60 * 60 ? 5 * 60
                : window.seconds() <= 24 * 60 * 60 ? 60 * 60 : 24 * 60 * 60;
        long first = Math.floorDiv(from.getEpochSecond(), bucketSeconds);
        long last = Math.floorDiv(now.getEpochSecond(), bucketSeconds);
        List<RuntimeOperationsSnapshot.TrendPoint> points = new ArrayList<>();
        for (long bucket = first; bucket <= last; bucket++) {
            Instant start = Instant.ofEpochSecond(bucket * bucketSeconds);
            Instant end = start.plusSeconds(bucketSeconds);
            List<RuntimeSummary> bucketEvents = events.stream()
                    .filter(event -> !event.occurredAt().toInstant().isBefore(start)
                            && event.occurredAt().toInstant().isBefore(end))
                    .toList();
            RuntimeOperationsSnapshot.Totals totals = totals(bucketEvents);
            points.add(new RuntimeOperationsSnapshot.TrendPoint(BUCKET_FORMAT.format(start), totals.total(),
                    totals.successes(), totals.failures(), totals.timeouts(), totals.successRate(), latency(bucketEvents).p95Ms()));
        }
        return points;
    }

    private long count(List<RuntimeSummary> events, String status) {
        return events.stream().filter(event -> status.equals(event.status())).count();
    }

    private long percentile(List<Long> values, double percentile) {
        int index = (int) Math.ceil(values.size() * percentile) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private double percentage(long numerator, long denominator) {
        return denominator == 0 ? 0 : Math.round(numerator * 10000.0 / denominator) / 100.0;
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }
}
