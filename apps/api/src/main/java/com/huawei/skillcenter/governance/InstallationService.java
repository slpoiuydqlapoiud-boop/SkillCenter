package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.distribution.DistributionService;
import com.huawei.skillcenter.distribution.InstallManifest;
import com.huawei.skillcenter.distribution.InstallationRequest;
import com.huawei.skillcenter.distribution.DistributionAuthorizationService;
import com.huawei.skillcenter.distribution.DistributionResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class InstallationService {
    private final DistributionService distributionService;
    private final DistributionAuthorizationService authorizationService;
    private final GovernanceStore store;

    public InstallationService(DistributionService distributionService,
                               DistributionAuthorizationService authorizationService,
                               GovernanceStore store) {
        this.distributionService = distributionService;
        this.authorizationService = authorizationService;
        this.store = store;
    }

    public DistributionResponse createManifest(String skillId, InstallationRequest request, Actor actor, String requestId) {
        RoleGuard.require(actor, Set.of("viewer", "maintainer", "reviewer", "admin"));
        InstallManifest manifest = distributionService.createManifest(skillId, request);
        Instant now = Instant.now();
        String clientType = request == null || request.clientType() == null || request.clientType().isBlank()
                ? "codex" : request.clientType();
        String clientVersion = request == null || request.clientVersion() == null ? "" : request.clientVersion();
        InstallationRecord record = new InstallationRecord(UUID.randomUUID().toString(), manifest.manifestId().toString(),
                skillId, manifest.skill().version(), clientType, clientVersion, actor.userId(), "requested", now, now,
                null, null, normalizeMethod(request == null ? null : request.method()), null, null, null, null);
        AuditEvent audit = new AuditEvent(UUID.randomUUID().toString(), "INSTALL_REQUESTED", "INSTALLATION",
                record.installationId(), actor.userId(), actor.role(), requestId, now,
                Map.of("skillId", skillId, "version", manifest.skill().version(), "clientType", clientType));
        store.addInstallation(record, audit);
        DistributionAuthorizationService.IssuedAuthorization issued = authorizationService.issue(manifest, record, request, actor, requestId);
        String downloadUrl = "/api/v1/distribution/artifacts/" + skillId + "/" + manifest.skill().version()
                + "?token=" + issued.token();
        DistributionResponse.Authorization authorization = new DistributionResponse.Authorization(
                issued.tokenId(), issued.token(), issued.authorization().expiresAt(), issued.authorization().method(),
                downloadUrl, null);
        String cliCommand = "skillctl install --skill " + skillId + "@" + manifest.skill().version()
                + " --token " + issued.token();
        return DistributionResponse.from(manifest, record.installationId(), authorization, cliCommand);
    }

    public List<InstallationRecord> list(Actor actor) {
        RoleGuard.require(actor, Set.of("viewer", "maintainer", "reviewer", "admin"));
        return store.snapshot().installations().stream()
                .filter(record -> "reviewer".equals(actor.role()) || "admin".equals(actor.role())
                        || record.requestedBy().equals(actor.userId()))
                .toList();
    }

    public InstallationRecord get(String installationId, Actor actor) {
        RoleGuard.require(actor, Set.of("viewer", "maintainer", "reviewer", "admin"));
        InstallationRecord installation = store.findInstallation(installationId)
                .orElseThrow(() -> new InstallationNotFoundException("Installation not found"));
        boolean privileged = "reviewer".equals(actor.role()) || "admin".equals(actor.role());
        if (!privileged && !installation.requestedBy().equals(actor.userId())) {
            throw new InstallationNotFoundException("Installation not found");
        }
        return installation;
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
}
