package com.huawei.skillcenter.governance;

import com.huawei.skillcenter.distribution.DistributionAuthorization;
import com.huawei.skillcenter.events.InvocationEvent;
import com.huawei.skillcenter.notification.NotificationRecord;

import java.util.List;

public record GovernanceSnapshot(
        List<SkillVersion> versions,
        List<ReviewTask> reviews,
        List<InstallationRecord> installations,
        List<AuditEvent> audits,
        List<DistributionAuthorization> authorizations,
        List<FavoriteRecord> favorites,
        GovernanceConfiguration configuration,
        List<InvocationEvent> invocationEvents,
        List<ExportJob> exportJobs,
        RetentionPolicy retentionPolicy,
        List<AuditIntegrityEntry> auditIntegrity,
        List<NotificationRecord> notifications
) {
    public GovernanceSnapshot {
        versions = List.copyOf(versions == null ? List.of() : versions);
        reviews = List.copyOf(reviews == null ? List.of() : reviews);
        installations = List.copyOf(installations == null ? List.of() : installations);
        audits = List.copyOf(audits == null ? List.of() : audits);
        authorizations = List.copyOf(authorizations == null ? List.of() : authorizations);
        favorites = List.copyOf(favorites == null ? List.of() : favorites);
        configuration = configuration == null ? GovernanceConfiguration.empty() : configuration;
        invocationEvents = List.copyOf(invocationEvents == null ? List.of() : invocationEvents);
        exportJobs = List.copyOf(exportJobs == null ? List.of() : exportJobs);
        retentionPolicy = retentionPolicy == null ? RetentionPolicy.defaults() : retentionPolicy;
        auditIntegrity = List.copyOf(auditIntegrity == null ? List.of() : auditIntegrity);
        notifications = List.copyOf(notifications == null ? List.of() : notifications);
    }

    public GovernanceSnapshot(List<SkillVersion> versions,
                              List<ReviewTask> reviews,
                              List<InstallationRecord> installations,
                              List<AuditEvent> audits,
                              List<DistributionAuthorization> authorizations,
                              List<FavoriteRecord> favorites,
                              GovernanceConfiguration configuration,
                              List<InvocationEvent> invocationEvents,
                              List<ExportJob> exportJobs,
                              RetentionPolicy retentionPolicy,
                              List<AuditIntegrityEntry> auditIntegrity) {
        this(versions, reviews, installations, audits, authorizations, favorites, configuration,
                invocationEvents, exportJobs, retentionPolicy, auditIntegrity, List.of());
    }

    public GovernanceSnapshot(List<SkillVersion> versions,
                              List<ReviewTask> reviews,
                              List<InstallationRecord> installations,
                              List<AuditEvent> audits) {
        this(versions, reviews, installations, audits, List.of(), List.of(), GovernanceConfiguration.empty(),
                List.of(), List.of(), RetentionPolicy.defaults(), List.of());
    }

    public GovernanceSnapshot(List<SkillVersion> versions,
                              List<ReviewTask> reviews,
                              List<InstallationRecord> installations,
                              List<AuditEvent> audits,
                              List<DistributionAuthorization> authorizations) {
        this(versions, reviews, installations, audits, authorizations, List.of(), GovernanceConfiguration.empty(),
                List.of(), List.of(), RetentionPolicy.defaults(), List.of());
    }

    public GovernanceSnapshot(List<SkillVersion> versions,
                              List<ReviewTask> reviews,
                              List<InstallationRecord> installations,
                              List<AuditEvent> audits,
                              List<DistributionAuthorization> authorizations,
                              List<FavoriteRecord> favorites) {
        this(versions, reviews, installations, audits, authorizations, favorites, GovernanceConfiguration.empty(),
                List.of(), List.of(), RetentionPolicy.defaults(), List.of());
    }

    public GovernanceSnapshot(List<SkillVersion> versions,
                              List<ReviewTask> reviews,
                              List<InstallationRecord> installations,
                              List<AuditEvent> audits,
                              List<DistributionAuthorization> authorizations,
                              List<FavoriteRecord> favorites,
                              GovernanceConfiguration configuration) {
        this(versions, reviews, installations, audits, authorizations, favorites, configuration,
                List.of(), List.of(), RetentionPolicy.defaults(), List.of());
    }

    public static GovernanceSnapshot empty() {
        return new GovernanceSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                GovernanceConfiguration.empty(), List.of(), List.of(), RetentionPolicy.defaults(), List.of(), List.of());
    }
}
