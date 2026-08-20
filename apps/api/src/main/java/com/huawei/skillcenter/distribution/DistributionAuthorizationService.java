package com.huawei.skillcenter.distribution;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.AuditEvent;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.InstallationRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Map;
import java.util.Optional;

@Service
public class DistributionAuthorizationService {
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    private final GovernanceStore store;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public DistributionAuthorizationService(GovernanceStore store) {
        this(store, Clock.systemUTC());
    }

    public DistributionAuthorizationService(GovernanceStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public IssuedAuthorization issue(InstallManifest manifest,
                                     InstallationRecord installation,
                                     InstallationRequest request,
                                     Actor actor) {
        return issue(manifest, installation, request, actor, "distribution");
    }

    public IssuedAuthorization issue(InstallManifest manifest,
                                     InstallationRecord installation,
                                     InstallationRequest request,
                                     Actor actor,
                                     String requestId) {
        if (manifest == null || manifest.skill() == null) {
            throw new IllegalArgumentException("Install manifest is required");
        }
        if ("withdrawn".equalsIgnoreCase(manifest.skill().status())) {
            throw new WithdrawnVersionUnavailableException("Version has been withdrawn");
        }
        Instant issuedAt = clock.instant();
        String token = createToken();
        String tokenId = UUID.randomUUID().toString();
        String method = normalizeMethod(request == null ? null : request.method());
        DistributionAuthorization authorization = new DistributionAuthorization(
                tokenId,
                digest(token),
                manifest.skill().id(),
                manifest.skill().version(),
                installation.installationId(),
                actor.userId(),
                installation.clientType(),
                installation.clientVersion(),
                method,
                issuedAt,
                issuedAt.plus(DEFAULT_TTL),
                null,
                null,
                null);
        store.addAuthorization(authorization, new AuditEvent(
                UUID.randomUUID().toString(), "DISTRIBUTION_AUTHORIZATION_ISSUED", "DISTRIBUTION_AUTHORIZATION",
                tokenId, actor.userId(), actor.role(), requestId, issuedAt,
                Map.of("skillId", manifest.skill().id(), "version", manifest.skill().version(), "method", method)));
        return new IssuedAuthorization(tokenId, token, authorization);
    }

    public DistributionAuthorization consume(String token) {
        String digest = digestToken(token);
        try {
            return store.consumeAuthorization(digest, clock.instant());
        } catch (IllegalStateException exception) {
            throw new DistributionAuthorizationException(exception.getMessage());
        }
    }

    public DistributionAuthorization consume(String expectedTokenId, String token) {
        DistributionAuthorization authorization = lookup(token);
        if (!expectedTokenId.equals(authorization.tokenId())) {
            throw new DistributionAuthorizationException("authorization does not match token id");
        }
        return consume(token);
    }

    public DistributionAuthorization lookup(String token) {
        String digest = digestToken(token);
        return store.findAuthorizationByDigest(digest)
                .orElseThrow(() -> new DistributionAuthorizationException("authorization was not found"));
    }

    private String digestToken(String token) {
        if (token == null || token.isBlank()) {
            throw new DistributionAuthorizationException("authorization token is malformed");
        }
        return digest(token);
    }

    private String createToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash distribution authorization", exception);
        }
    }

    private String normalizeMethod(String method) {
        if ("cli".equalsIgnoreCase(method)) {
            return "cli";
        }
        if ("manual-zip".equalsIgnoreCase(method) || "download".equalsIgnoreCase(method)) {
            return "manual-zip";
        }
        return "one-click";
    }

    public record IssuedAuthorization(String tokenId, String token, DistributionAuthorization authorization) {
    }
}
