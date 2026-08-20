package com.huawei.skillcenter.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RateLimitService {
    private final Clock clock;
    private final Map<String, RateLimitRule> rules;
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Autowired
    public RateLimitService(SecurityBoundaryProperties properties) {
        this(Clock.systemUTC(), rulesFrom(properties));
    }

    public RateLimitService(Clock clock, Map<String, RateLimitRule> rules) {
        this.clock = clock;
        this.rules = Map.copyOf(rules);
    }

    public RateLimitDecision check(String scope, String subject) {
        RateLimitRule rule = rules.get(scope);
        if (rule == null) {
            return RateLimitDecision.unlimited();
        }
        String safeSubject = subject == null || subject.isBlank() ? "anonymous" : subject;
        long now = clock.instant().getEpochSecond();
        long windowSeconds = rule.window().toSeconds();
        AtomicReference<RateLimitDecision> decision = new AtomicReference<>();
        counters.compute(scope + "\u0000" + safeSubject, (key, existing) -> {
            WindowCounter counter = existing;
            if (counter == null || now >= counter.resetEpochSeconds()) {
                long reset = now + windowSeconds;
                counter = new WindowCounter(1, reset);
                decision.set(new RateLimitDecision(true, rule.limit(), rule.limit() - 1,
                        Math.max(1, reset - now), reset));
                return counter;
            }
            if (counter.count() < rule.limit()) {
                counter = new WindowCounter(counter.count() + 1, counter.resetEpochSeconds());
                decision.set(new RateLimitDecision(true, rule.limit(), rule.limit() - counter.count(),
                        Math.max(1, counter.resetEpochSeconds() - now), counter.resetEpochSeconds()));
                return counter;
            }
            decision.set(new RateLimitDecision(false, rule.limit(), 0,
                    Math.max(1, counter.resetEpochSeconds() - now), counter.resetEpochSeconds()));
            return counter;
        });
        return decision.get();
    }

    public void clear() {
        counters.clear();
    }

    private static Map<String, RateLimitRule> rulesFrom(SecurityBoundaryProperties properties) {
        SecurityBoundaryProperties.RateLimitProperties configured = properties.getRateLimit();
        Duration window = Duration.ofSeconds(Math.max(1, configured.getWindowSeconds()));
        return Map.of(
                "INSTALLATION_CREATE", new RateLimitRule(configured.getInstallationCreate(), window),
                "DISTRIBUTION_CONSUME", new RateLimitRule(configured.getDistributionConsume(), window),
                "DISTRIBUTION_DOWNLOAD", new RateLimitRule(configured.getDistributionDownload(), window),
                "INVOCATION_INGEST", new RateLimitRule(configured.getInvocationIngest(), window),
                "EXPORT_CREATE", new RateLimitRule(configured.getExportCreate(), window));
    }

    private record WindowCounter(int count, long resetEpochSeconds) {
    }
}
