package com.huawei.skillcenter.notification;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceStore;
import com.huawei.skillcenter.governance.RoleGuard;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public class NotificationService {
    private final GovernanceStore store;

    public NotificationService(GovernanceStore store) {
        this.store = store;
    }

    public List<NotificationRecord> list(Actor actor) {
        requireActor(actor);
        return store.notificationsForUser(actor.userId()).stream()
                .sorted(Comparator.comparing(NotificationRecord::createdAt).reversed()
                        .thenComparing(NotificationRecord::notificationId))
                .toList();
    }

    public NotificationRecord markRead(String notificationId, Actor actor) {
        requireActor(actor);
        return store.markNotificationRead(actor.userId(), notificationId, Instant.now());
    }

    public List<NotificationRecord> markAllRead(Actor actor) {
        requireActor(actor);
        return store.markAllNotificationsRead(actor.userId(), Instant.now());
    }

    private void requireActor(Actor actor) {
        RoleGuard.require(actor, Set.of("developer", "admin", "viewer", "maintainer", "reviewer"));
    }
}
