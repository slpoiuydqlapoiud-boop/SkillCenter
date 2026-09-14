package com.huawei.skillcenter.governance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "skill-center.security.authentication")
public class ActorAuthenticationProperties {
    private String mode = "local";
    private LocalProperties local = new LocalProperties();
    private JwtProperties jwt = new JwtProperties();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode == null || mode.isBlank() ? "local" : mode.trim();
    }

    public JwtProperties getJwt() {
        return jwt;
    }

    public void setJwt(JwtProperties jwt) {
        this.jwt = jwt == null ? new JwtProperties() : jwt;
    }

    public LocalProperties getLocal() {
        return local;
    }

    public void setLocal(LocalProperties local) {
        this.local = local == null ? new LocalProperties() : local;
    }

    public static class LocalProperties {
        private String username = "admin";
        private String password = "";
        private String passwordHash = "";
        private String role = "ADMIN";
        private boolean requireToken;
        private boolean allowGuest = true;
        private String guestUserId = "developer-user";
        private String guestRole = "MEMBER";
        private long tokenTtlSeconds = 28_800;
        private Map<String, LocalAccount> accounts = new LinkedHashMap<>();

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username == null ? "" : username.trim();
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password == null ? "" : password;
        }

        public String getPasswordHash() {
            return passwordHash;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash == null ? "" : passwordHash.trim();
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role == null || role.isBlank() ? "ADMIN" : role.trim();
        }

        public boolean isRequireToken() {
            return requireToken;
        }

        public void setRequireToken(boolean requireToken) {
            this.requireToken = requireToken;
        }

        public boolean isAllowGuest() {
            return allowGuest;
        }

        public void setAllowGuest(boolean allowGuest) {
            this.allowGuest = allowGuest;
        }

        public String getGuestUserId() {
            return guestUserId;
        }

        public void setGuestUserId(String guestUserId) {
            this.guestUserId = guestUserId == null ? "developer-user" : guestUserId.trim();
        }

        public String getGuestRole() {
            return guestRole;
        }

        public void setGuestRole(String guestRole) {
            this.guestRole = guestRole == null || guestRole.isBlank() ? "MEMBER" : guestRole.trim();
        }

        public long getTokenTtlSeconds() {
            return tokenTtlSeconds;
        }

        public void setTokenTtlSeconds(long tokenTtlSeconds) {
            if (tokenTtlSeconds < 60 || tokenTtlSeconds > 86_400) {
                throw new IllegalArgumentException("local auth token TTL must be between 60 and 86400 seconds");
            }
            this.tokenTtlSeconds = tokenTtlSeconds;
        }

        public Map<String, LocalAccount> getAccounts() {
            return accounts;
        }

        public void setAccounts(Map<String, LocalAccount> accounts) {
            this.accounts = accounts == null ? new LinkedHashMap<>() : new LinkedHashMap<>(accounts);
        }
    }

    public static class LocalAccount {
        private String passwordHash = "";
        private String password = "";
        private String role = "MEMBER";

        public String getPasswordHash() {
            return passwordHash;
        }

        public void setPasswordHash(String passwordHash) {
            this.passwordHash = passwordHash == null ? "" : passwordHash.trim();
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password == null ? "" : password;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role == null || role.isBlank() ? "MEMBER" : role.trim();
        }
    }

    public static class JwtProperties {
        private static final int MIN_CONNECT_TIMEOUT_MS = 100;
        private static final int MAX_CONNECT_TIMEOUT_MS = 10_000;
        private static final int MIN_REQUEST_TIMEOUT_MS = 100;
        private static final int MAX_REQUEST_TIMEOUT_MS = 15_000;
        private static final long MIN_CACHE_TTL_SECONDS = 1;
        private static final long MAX_CACHE_TTL_SECONDS = 86_400;
        private static final int MIN_RESPONSE_BYTES = 4_096;
        private static final int MAX_RESPONSE_BYTES = 1_048_576;
        private String publicKey = "";
        private String jwksUri = "";
        private String teamClaim = "teams";
        private boolean teamClaimRequired;
        private String issuer = "";
        private String audience = "";
        private long clockSkewSeconds = 30;
        private int connectTimeoutMs = 1_000;
        private int requestTimeoutMs = 2_000;
        private long cacheTtlSeconds = 300;
        private int maxResponseBytes = 262_144;

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey == null ? "" : publicKey.trim();
        }

        public String getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(String jwksUri) {
            this.jwksUri = jwksUri == null ? "" : jwksUri.trim();
        }

        public String getTeamClaim() {
            return teamClaim;
        }

        public void setTeamClaim(String teamClaim) {
            String normalized = teamClaim == null ? "" : teamClaim.trim();
            if (!normalized.isBlank() && !normalized.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}")) {
                throw new IllegalArgumentException("JWT team claim is invalid");
            }
            this.teamClaim = normalized;
        }

        public boolean isTeamClaimRequired() {
            return teamClaimRequired;
        }

        public void setTeamClaimRequired(boolean teamClaimRequired) {
            this.teamClaimRequired = teamClaimRequired;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer == null ? "" : issuer.trim();
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience == null ? "" : audience.trim();
        }

        public long getClockSkewSeconds() {
            return clockSkewSeconds;
        }

        public void setClockSkewSeconds(long clockSkewSeconds) {
            if (clockSkewSeconds < 0 || clockSkewSeconds > 300) {
                throw new IllegalArgumentException("JWT clock skew must be between 0 and 300 seconds");
            }
            this.clockSkewSeconds = clockSkewSeconds;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            if (connectTimeoutMs < MIN_CONNECT_TIMEOUT_MS || connectTimeoutMs > MAX_CONNECT_TIMEOUT_MS) {
                throw new IllegalArgumentException("JWT connect timeout is out of bounds");
            }
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getRequestTimeoutMs() {
            return requestTimeoutMs;
        }

        public void setRequestTimeoutMs(int requestTimeoutMs) {
            if (requestTimeoutMs < MIN_REQUEST_TIMEOUT_MS || requestTimeoutMs > MAX_REQUEST_TIMEOUT_MS) {
                throw new IllegalArgumentException("JWT request timeout is out of bounds");
            }
            this.requestTimeoutMs = requestTimeoutMs;
        }

        public long getCacheTtlSeconds() {
            return cacheTtlSeconds;
        }

        public void setCacheTtlSeconds(long cacheTtlSeconds) {
            if (cacheTtlSeconds < MIN_CACHE_TTL_SECONDS || cacheTtlSeconds > MAX_CACHE_TTL_SECONDS) {
                throw new IllegalArgumentException("JWT cache TTL is out of bounds");
            }
            this.cacheTtlSeconds = cacheTtlSeconds;
        }

        public int getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(int maxResponseBytes) {
            if (maxResponseBytes < MIN_RESPONSE_BYTES || maxResponseBytes > MAX_RESPONSE_BYTES) {
                throw new IllegalArgumentException("JWT JWKS response size is out of bounds");
            }
            this.maxResponseBytes = maxResponseBytes;
        }
    }
}
