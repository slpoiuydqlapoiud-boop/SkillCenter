package com.huawei.skillcenter.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.distribution.DistributionAuthorization;
import com.huawei.skillcenter.skill.SkillRecord;
import com.huawei.skillcenter.skill.SkillRepository;
import com.huawei.skillcenter.skill.SkillQuery;
import com.huawei.skillcenter.notification.NotificationRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class GovernanceStore {
    private final Path statePath;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AuditIntegrityService auditIntegrityService = new AuditIntegrityService();
    private GovernanceSnapshot current;

    public GovernanceStore(Path statePath, List<SkillRecord> seedSkills) {
        this(statePath, new ObjectMapper().findAndRegisterModules(), seedSkills);
    }

    @Autowired
    public GovernanceStore(ObjectMapper objectMapper,
                           @Value("${skill-center.governance-storage:./data/governance/state.json}") String statePath,
                           SkillRepository skillRepository) {
        this(Path.of(statePath), objectMapper,
                skillRepository.findPublished(new SkillQuery("", "", "", "", 1, 50)).items());
    }

    private GovernanceStore(Path statePath, ObjectMapper objectMapper, List<SkillRecord> seedSkills) {
        this.statePath = statePath.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.current = loadOrSeed(seedSkills);
    }

    public GovernanceSnapshot snapshot() {
        lock.readLock().lock();
        try {
            return current;
        } finally {
            lock.readLock().unlock();
        }
    }

    public GovernanceSnapshot createPendingVersion(SkillVersion version, ReviewTask review, AuditEvent audit) {
        return mutate(snapshot -> {
            List<SkillVersion> versions = new ArrayList<>(snapshot.versions());
            List<ReviewTask> reviews = new ArrayList<>(snapshot.reviews());
            List<AuditEvent> audits = new ArrayList<>(snapshot.audits());
            versions.add(version);
            reviews.add(review);
            audits.add(audit);
            return copyWith(snapshot, versions, reviews, snapshot.installations(), audits,
                    snapshot.authorizations(), snapshot.favorites(), snapshot.configuration());
        });
    }

    public GovernanceSnapshot updateReview(String reviewId, ReviewTask review, SkillVersion version, AuditEvent audit) {
        return mutate(snapshot -> {
            List<ReviewTask> reviews = snapshot.reviews().stream()
                    .map(existing -> existing.reviewId().equals(reviewId) ? review : existing).toList();
            List<SkillVersion> versions = snapshot.versions().stream()
                    .map(existing -> existing.packageId().equals(version.packageId()) ? version : existing).toList();
            List<AuditEvent> audits = new ArrayList<>(snapshot.audits());
            audits.add(audit);
            return copyWith(snapshot, versions, reviews, snapshot.installations(), audits,
                    snapshot.authorizations(), snapshot.favorites(), snapshot.configuration());
        });
    }

    public GovernanceSnapshot addInstallation(InstallationRecord installation, AuditEvent audit) {
        return mutate(snapshot -> {
            List<InstallationRecord> installations = new ArrayList<>(snapshot.installations());
            List<AuditEvent> audits = new ArrayList<>(snapshot.audits());
            installations.add(installation);
            if (audit != null) {
                audits.add(audit);
            }
            return copyWith(snapshot, snapshot.versions(), snapshot.reviews(), installations, audits,
                    snapshot.authorizations(), snapshot.favorites(), snapshot.configuration());
        });
    }

    public Optional<InstallationRecord> findInstallation(String installationId) {
        lock.readLock().lock();
        try {
            return current.installations().stream()
                    .filter(installation -> installation.installationId().equals(installationId))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public GovernanceSnapshot updateInstallation(InstallationRecord installation, AuditEvent audit) {
        return mutate(snapshot -> {
            List<InstallationRecord> installations = snapshot.installations().stream()
                    .map(existing -> existing.installationId().equals(installation.installationId()) ? installation : existing)
                    .toList();
            List<AuditEvent> audits = new ArrayList<>(snapshot.audits());
            if (audit != null) {
                audits.add(audit);
            }
            return copyWith(snapshot, snapshot.versions(), snapshot.reviews(), installations,
                    audits, snapshot.authorizations(), snapshot.favorites(), snapshot.configuration());
        });
    }

    public GovernanceSnapshot addAuthorization(DistributionAuthorization authorization) {
        return addAuthorization(authorization, null);
    }

    public GovernanceSnapshot addAuthorization(DistributionAuthorization authorization, AuditEvent audit) {
        return mutate(snapshot -> {
            List<DistributionAuthorization> authorizations = new ArrayList<>(snapshot.authorizations());
            authorizations.add(authorization);
            List<AuditEvent> audits = new ArrayList<>(snapshot.audits());
            if (audit != null) {
                audits.add(audit);
            }
            return copyWith(snapshot, snapshot.versions(), snapshot.reviews(), snapshot.installations(),
                    audits, authorizations, snapshot.favorites(), snapshot.configuration());
        });
    }

    public GovernanceSnapshot addAudit(AuditEvent audit) {
        return mutate(snapshot -> {
            List<AuditEvent> audits = new ArrayList<>(snapshot.audits());
            audits.add(audit);
            return copyWith(snapshot, snapshot.versions(), snapshot.reviews(), snapshot.installations(),
                    audits, snapshot.authorizations(), snapshot.favorites(), snapshot.configuration());
        });
    }

    public GovernanceSnapshot updateVersion(SkillVersion version, AuditEvent audit) {
        return mutate(snapshot -> {
            List<SkillVersion> versions = snapshot.versions().stream()
                    .map(existing -> existing.packageId().equals(version.packageId()) ? version : existing)
                    .toList();
            List<AuditEvent> audits = new ArrayList<>(snapshot.audits());
            if (audit != null) {
                audits.add(audit);
            }
            return copyWith(snapshot, versions, snapshot.reviews(), snapshot.installations(), audits,
                    snapshot.authorizations(), snapshot.favorites(), snapshot.configuration());
        });
    }

    public GovernanceSnapshot addFavorite(FavoriteRecord favorite, AuditEvent audit) {
        return mutate(snapshot -> {
            List<FavoriteRecord> favorites = new ArrayList<>(snapshot.favorites());
            boolean exists = favorites.stream().anyMatch(existing -> existing.userId().equals(favorite.userId())
                    && existing.skillId().equals(favorite.skillId()));
            if (!exists) {
                favorites.add(favorite);
            }
            List<AuditEvent> audits = new ArrayList<>(snapshot.audits());
            if (audit != null && !exists) {
                audits.add(audit);
            }
            return copyWith(snapshot, snapshot.versions(), snapshot.reviews(), snapshot.installations(), audits,
                    snapshot.authorizations(), favorites, snapshot.configuration());
        });
    }

    public GovernanceSnapshot removeFavorite(String userId, String skillId, AuditEvent audit) {
        return mutate(snapshot -> {
            List<FavoriteRecord> favorites = snapshot.favorites().stream()
                    .filter(existing -> !(existing.userId().equals(userId) && existing.skillId().equals(skillId)))
                    .toList();
            boolean removed = favorites.size() != snapshot.favorites().size();
            List<AuditEvent> audits = new ArrayList<>(snapshot.audits());
            if (audit != null && removed) {
                audits.add(audit);
            }
            return copyWith(snapshot, snapshot.versions(), snapshot.reviews(), snapshot.installations(), audits,
                    snapshot.authorizations(), favorites, snapshot.configuration());
        });
    }

    public GovernanceSnapshot updateGovernanceConfiguration(GovernanceConfiguration configuration, AuditEvent audit) {
        return mutate(snapshot -> {
            List<AuditEvent> audits = new ArrayList<>(snapshot.audits());
            if (audit != null) {
                audits.add(audit);
            }
            return copyWith(snapshot, snapshot.versions(), snapshot.reviews(), snapshot.installations(), audits,
                    snapshot.authorizations(), snapshot.favorites(),
                    configuration == null ? GovernanceConfiguration.empty() : configuration);
        });
    }

    public GovernanceSnapshot addExportJob(ExportJob job) {
        return mutate(snapshot -> {
            List<ExportJob> jobs = new ArrayList<>(snapshot.exportJobs());
            jobs.add(job);
            return withM52(snapshot, snapshot.invocationEvents(), jobs, snapshot.retentionPolicy(),
                    snapshot.auditIntegrity());
        });
    }

    public GovernanceSnapshot updateExportJob(ExportJob job) {
        return mutate(snapshot -> {
            List<ExportJob> jobs = snapshot.exportJobs().stream()
                    .map(existing -> existing.jobId().equals(job.jobId()) ? job : existing)
                    .toList();
            return withM52(snapshot, snapshot.invocationEvents(), jobs, snapshot.retentionPolicy(),
                    snapshot.auditIntegrity());
        });
    }

    public GovernanceSnapshot updateRetentionPolicy(RetentionPolicy policy) {
        return mutate(snapshot -> withM52(snapshot, snapshot.invocationEvents(), snapshot.exportJobs(),
                policy, snapshot.auditIntegrity()));
    }

    public long countInstallationsBefore(Instant cutoff) {
        lock.readLock().lock();
        try {
            return current.installations().stream()
                    .filter(installation -> installation.requestedAt() != null
                            && installation.requestedAt().isBefore(cutoff))
                    .count();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long deleteInstallationsBefore(Instant cutoff) {
        long before = snapshot().installations().size();
        GovernanceSnapshot after = mutate(snapshot -> {
            List<InstallationRecord> retained = snapshot.installations().stream()
                    .filter(installation -> installation.requestedAt() == null
                            || !installation.requestedAt().isBefore(cutoff))
                    .toList();
            return copyWith(snapshot, snapshot.versions(), snapshot.reviews(), retained, snapshot.audits(),
                    snapshot.authorizations(), snapshot.favorites(), snapshot.configuration());
        });
        return before - after.installations().size();
    }

    public GovernanceSnapshot updateInvocationEvents(List<com.huawei.skillcenter.events.InvocationEvent> events) {
        return mutate(snapshot -> withM52(snapshot, events, snapshot.exportJobs(), snapshot.retentionPolicy(),
                snapshot.auditIntegrity()));
    }

    public GovernanceSnapshot updateAuditIntegrity(List<AuditIntegrityEntry> integrityEntries) {
        return mutate(snapshot -> withM52(snapshot, snapshot.invocationEvents(), snapshot.exportJobs(),
                snapshot.retentionPolicy(), integrityEntries));
    }

    public List<FavoriteRecord> favoritesForUser(String userId) {
        lock.readLock().lock();
        try {
            return current.favorites().stream()
                    .filter(favorite -> favorite.userId().equals(userId))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<NotificationRecord> notificationsForUser(String userId) {
        lock.readLock().lock();
        try {
            return current.notifications().stream()
                    .filter(notification -> notification.userId().equals(userId))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public GovernanceSnapshot addNotification(NotificationRecord notification) {
        if (notification == null) {
            throw new IllegalArgumentException("notification must not be null");
        }
        return mutate(snapshot -> {
            boolean exists = snapshot.notifications().stream().anyMatch(existing ->
                    existing.notificationId().equals(notification.notificationId())
                            && existing.userId().equals(notification.userId()));
            if (exists) {
                return snapshot;
            }
            List<NotificationRecord> notifications = new ArrayList<>(snapshot.notifications());
            notifications.add(notification);
            return withNotifications(snapshot, notifications);
        });
    }

    public NotificationRecord markNotificationRead(String userId, String notificationId, Instant at) {
        return mutate(snapshot -> {
            NotificationRecord existing = snapshot.notifications().stream()
                    .filter(notification -> notification.userId().equals(userId)
                            && notification.notificationId().equals(notificationId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("notification not found"));
            List<NotificationRecord> notifications = snapshot.notifications().stream()
                    .map(notification -> notification == existing ? existing.markRead(at) : notification)
                    .toList();
            return withNotifications(snapshot, notifications);
        }).notifications().stream()
                .filter(notification -> notification.userId().equals(userId)
                        && notification.notificationId().equals(notificationId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("notification not found"));
    }

    public List<NotificationRecord> markAllNotificationsRead(String userId, Instant at) {
        GovernanceSnapshot updated = mutate(snapshot -> withNotifications(snapshot, snapshot.notifications().stream()
                .map(notification -> notification.userId().equals(userId) && !notification.read()
                        ? notification.markRead(at) : notification)
                .toList()));
        return updated.notifications().stream()
                .filter(notification -> notification.userId().equals(userId))
                .toList();
    }

    public Optional<DistributionAuthorization> findAuthorizationByDigest(String tokenDigest) {
        lock.readLock().lock();
        try {
            return current.authorizations().stream()
                    .filter(authorization -> authorization.tokenDigest().equals(tokenDigest))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    public DistributionAuthorization consumeAuthorization(String tokenDigest, Instant now) {
        lock.writeLock().lock();
        try {
            DistributionAuthorization existing = current.authorizations().stream()
                    .filter(authorization -> authorization.tokenDigest().equals(tokenDigest))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("authorization not found"));
            if (existing.consumedAt() != null) {
                throw new IllegalStateException("authorization has already been consumed");
            }
            if (existing.revokedAt() != null) {
                throw new IllegalStateException("authorization has been revoked");
            }
            if (!now.isBefore(existing.expiresAt())) {
                throw new IllegalStateException("authorization has expired");
            }
            DistributionAuthorization consumed = new DistributionAuthorization(
                    existing.tokenId(), existing.tokenDigest(), existing.skillId(), existing.version(),
                    existing.installationId(), existing.requestedBy(), existing.clientType(), existing.clientVersion(),
                    existing.method(), existing.issuedAt(), existing.expiresAt(), now,
                    existing.revokedAt(), existing.revokeReason());
            List<DistributionAuthorization> authorizations = current.authorizations().stream()
                    .map(authorization -> authorization.tokenId().equals(existing.tokenId()) ? consumed : authorization)
                    .toList();
            GovernanceSnapshot next = copyWith(current, current.versions(), current.reviews(), current.installations(),
                    current.audits(), authorizations, current.favorites(), current.configuration());
            persist(next);
            current = next;
            return consumed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void reload() {
        lock.writeLock().lock();
        try {
            current = readState();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private GovernanceSnapshot mutate(java.util.function.UnaryOperator<GovernanceSnapshot> operation) {
        lock.writeLock().lock();
        try {
            GovernanceSnapshot next = operation.apply(current);
            persist(next);
            current = next;
            return next;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private GovernanceSnapshot copyWith(GovernanceSnapshot snapshot,
                                        List<SkillVersion> versions,
                                        List<ReviewTask> reviews,
                                        List<InstallationRecord> installations,
                                        List<AuditEvent> audits,
                                        List<DistributionAuthorization> authorizations,
                                        List<FavoriteRecord> favorites,
                                        GovernanceConfiguration configuration) {
        return new GovernanceSnapshot(versions, reviews, installations, audits, authorizations, favorites,
                configuration, snapshot.invocationEvents(), snapshot.exportJobs(), snapshot.retentionPolicy(),
                integrityFor(snapshot, audits), snapshot.notifications());
    }

    private GovernanceSnapshot withM52(GovernanceSnapshot snapshot,
                                       List<com.huawei.skillcenter.events.InvocationEvent> invocationEvents,
                                       List<ExportJob> exportJobs,
                                       RetentionPolicy retentionPolicy,
                                       List<AuditIntegrityEntry> auditIntegrity) {
        return new GovernanceSnapshot(snapshot.versions(), snapshot.reviews(), snapshot.installations(),
                snapshot.audits(), snapshot.authorizations(), snapshot.favorites(), snapshot.configuration(),
                invocationEvents, exportJobs, retentionPolicy, auditIntegrity, snapshot.notifications());
    }

    private GovernanceSnapshot withNotifications(GovernanceSnapshot snapshot, List<NotificationRecord> notifications) {
        return new GovernanceSnapshot(snapshot.versions(), snapshot.reviews(), snapshot.installations(),
                snapshot.audits(), snapshot.authorizations(), snapshot.favorites(), snapshot.configuration(),
                snapshot.invocationEvents(), snapshot.exportJobs(), snapshot.retentionPolicy(),
                snapshot.auditIntegrity(), notifications);
    }

    private List<AuditIntegrityEntry> integrityFor(GovernanceSnapshot snapshot, List<AuditEvent> audits) {
        List<AuditEvent> safeAudits = audits == null ? List.of() : audits;
        List<AuditIntegrityEntry> existing = snapshot.auditIntegrity() == null
                ? List.of() : snapshot.auditIntegrity();
        if (existing.size() > safeAudits.size()) {
            existing = List.of();
        }
        List<AuditIntegrityEntry> result = new ArrayList<>(existing);
        for (int index = result.size(); index < safeAudits.size(); index++) {
            result.add(auditIntegrityService.append(result, safeAudits.get(index)));
        }
        return result;
    }

    private GovernanceSnapshot loadOrSeed(List<SkillRecord> seedSkills) {
        if (Files.exists(statePath)) {
            return readState();
        }
        GovernanceSnapshot seeded = seedSkills.stream().map(skill -> new SkillVersion(
                        "seed-" + skill.id(), skill.id(), skill.version(), "published", "0".repeat(64), 0,
                        "", "system", Instant.parse(skill.publishedAt() + "T00:00:00Z"), "system",
                        Instant.parse(skill.publishedAt() + "T00:00:00Z"), null))
                .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(), versions ->
                        new GovernanceSnapshot(versions, List.of(), List.of(), List.of())));
        persist(seeded);
        return seeded;
    }

    private GovernanceSnapshot readState() {
        try {
            GovernanceSnapshot loaded = objectMapper.readValue(statePath.toFile(), GovernanceSnapshot.class);
            List<AuditIntegrityEntry> integrity = integrityFor(loaded, loaded.audits());
            if (integrity.size() == loaded.auditIntegrity().size()) {
                return loaded;
            }
            return new GovernanceSnapshot(loaded.versions(), loaded.reviews(), loaded.installations(), loaded.audits(),
                    loaded.authorizations(), loaded.favorites(), loaded.configuration(), loaded.invocationEvents(),
                    loaded.exportJobs(), loaded.retentionPolicy(), integrity, loaded.notifications());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read governance state", exception);
        }
    }

    private void persist(GovernanceSnapshot snapshot) {
        try {
            Path parent = statePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = statePath.resolveSibling(statePath.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), snapshot);
            try {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new GovernancePersistenceException(exception);
        }
    }

    public static class GovernancePersistenceException extends RuntimeException {
        public GovernancePersistenceException(Throwable cause) {
            super(cause);
        }
    }
}
