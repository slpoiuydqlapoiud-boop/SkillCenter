package com.huawei.skillcenter.governance;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Coordinates explicit directory sync without making authorization depend on the network. */
@Service
public class OrganizationDirectorySyncService {
    private final OrganizationDirectoryProperties properties;
    private final OrganizationDirectoryStore store;
    private final OrganizationDirectoryClient client;
    private final GovernanceStore governanceStore;
    private final Clock clock;

    @Autowired
    public OrganizationDirectorySyncService(OrganizationDirectoryProperties properties,
                                            OrganizationDirectoryStore store,
                                            OrganizationDirectoryClient client,
                                            GovernanceStore governanceStore) {
        this(properties, store, client, governanceStore, Clock.systemUTC());
    }

    public OrganizationDirectorySyncService(OrganizationDirectoryProperties properties,
                                            OrganizationDirectoryStore store,
                                            OrganizationDirectoryClient client,
                                            GovernanceStore governanceStore, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.client = client;
        this.governanceStore = governanceStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public OrganizationDirectoryStatus status() {
        if ("local".equals(properties.getMode())) return OrganizationDirectoryStatus.local();
        return OrganizationDirectoryStatus.from(store.state(), clock.instant(), properties.getMaxAgeSeconds());
    }

    public boolean isLocalMode() {
        return "local".equals(properties.getMode());
    }

    public boolean allows(Actor actor, String teamId) {
        if (actor == null || teamId == null || teamId.isBlank()) return false;
        if (isLocalMode()) return true;
        return store.activeSnapshot(clock.instant(), properties.getMaxAgeSeconds())
                .flatMap(snapshot -> snapshot.team(teamId))
                .filter(team -> "active".equals(team.status()))
                .filter(team -> team.memberUserIds().contains(actor.userId()))
                .map(team -> !actor.teamClaimsAuthoritative() || actor.teamIds().contains(teamId))
                .orElse(false);
    }

    public OrganizationDirectoryStatus sync(Actor actor, String requestId) {
        properties.validate();
        if ("local".equals(properties.getMode())) return OrganizationDirectoryStatus.local();
        Instant attemptedAt = clock.instant();
        try {
            OrganizationDirectorySnapshot snapshot = client.fetch();
            OrganizationDirectoryState state = store.accept(snapshot, attemptedAt);
            audit("ORGANIZATION_DIRECTORY_SYNCED", actor, requestId, state, "");
            return OrganizationDirectoryStatus.from(state, attemptedAt, properties.getMaxAgeSeconds());
        } catch (OrganizationDirectoryRevisionConflictException exception) {
            OrganizationDirectoryState state = store.markFailure("DIRECTORY_REVISION_CONFLICT", attemptedAt);
            audit("ORGANIZATION_DIRECTORY_SYNC_FAILED", actor, requestId, state, "DIRECTORY_REVISION_CONFLICT");
            throw exception;
        } catch (OrganizationDirectoryUnavailableException exception) {
            OrganizationDirectoryState state = store.markFailure(exception.reasonCode(), attemptedAt);
            audit("ORGANIZATION_DIRECTORY_SYNC_FAILED", actor, requestId, state, exception.reasonCode());
            throw exception;
        } catch (IllegalArgumentException exception) {
            OrganizationDirectoryState state = store.markFailure("DIRECTORY_SNAPSHOT_INVALID", attemptedAt);
            audit("ORGANIZATION_DIRECTORY_SYNC_FAILED", actor, requestId, state, "DIRECTORY_SNAPSHOT_INVALID");
            throw new OrganizationDirectoryUnavailableException("DIRECTORY_SNAPSHOT_INVALID");
        }
    }

    private void audit(String action, Actor actor, String requestId, OrganizationDirectoryState state, String reason) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (!state.source().isBlank()) metadata.put("source", state.source());
        if (!state.revision().isBlank()) metadata.put("revision", state.revision());
        metadata.put("teamCount", Integer.toString(state.teamCount()));
        metadata.put("memberCount", Integer.toString(state.memberCount()));
        metadata.put("status", state.status());
        if (!reason.isBlank()) metadata.put("reasonCode", reason);
        String actorId = actor == null || actor.userId() == null ? "system" : actor.userId();
        String actorRole = actor == null || actor.role() == null ? "system" : actor.role();
        governanceStore.addAudit(new AuditEvent("org-dir-" + UUID.randomUUID(), action,
                "ORGANIZATION_DIRECTORY", state.source().isBlank() ? "organization-directory" : state.source(),
                actorId, actorRole, requestId == null ? "" : requestId, clock.instant(), Map.copyOf(metadata)));
    }
}
