package com.huawei.skillcenter.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "skill-center.security")
public class SecurityBoundaryProperties {
    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://127.0.0.1:5173",
            "http://localhost:5173"));
    private long idempotencyTtlSeconds = 900;
    private RateLimitProperties rateLimit = new RateLimitProperties();

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : new ArrayList<>(allowedOrigins);
    }

    public long getIdempotencyTtlSeconds() {
        return idempotencyTtlSeconds;
    }

    public void setIdempotencyTtlSeconds(long idempotencyTtlSeconds) {
        this.idempotencyTtlSeconds = idempotencyTtlSeconds;
    }

    public RateLimitProperties getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimitProperties rateLimit) {
        this.rateLimit = rateLimit == null ? new RateLimitProperties() : rateLimit;
    }

    public static class RateLimitProperties {
        private long windowSeconds = 60;
        private int installationCreate = 30;
        private int distributionConsume = 60;
        private int distributionDownload = 60;
        private int invocationIngest = 120;
        private int runtimeSummaryIngest = 120;
        private int exportCreate = 10;

        public long getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(long value) { windowSeconds = value; }
        public int getInstallationCreate() { return installationCreate; }
        public void setInstallationCreate(int value) { installationCreate = value; }
        public int getDistributionConsume() { return distributionConsume; }
        public void setDistributionConsume(int value) { distributionConsume = value; }
        public int getDistributionDownload() { return distributionDownload; }
        public void setDistributionDownload(int value) { distributionDownload = value; }
        public int getInvocationIngest() { return invocationIngest; }
        public void setInvocationIngest(int value) { invocationIngest = value; }
        public int getRuntimeSummaryIngest() { return runtimeSummaryIngest; }
        public void setRuntimeSummaryIngest(int value) { runtimeSummaryIngest = value; }
        public int getExportCreate() { return exportCreate; }
        public void setExportCreate(int value) { exportCreate = value; }
    }
}
