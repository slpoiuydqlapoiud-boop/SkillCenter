package com.huawei.skillcenter.notification;

import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.GovernanceStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationServiceTest {
    @Test
    void listsOnlyTheCurrentUserAndCanMarkOneRead() throws Exception {
        Path state = Files.createTempDirectory("skill-center-notifications").resolve("state.json");
        GovernanceStore store = new GovernanceStore(state, List.of());
        NotificationService service = new NotificationService(store);
        store.addNotification(new NotificationRecord("n-1", "developer-1", "review", "已发布",
                "Skill 已进入市场", "check", Instant.parse("2026-08-18T10:00:00Z"), false, null));
        store.addNotification(new NotificationRecord("n-2", "developer-2", "review", "其他用户",
                "不应可见", "bell", Instant.parse("2026-08-18T11:00:00Z"), false, null));

        assertThat(service.list(new Actor("developer-1", "developer"))).extracting(NotificationRecord::notificationId)
                .containsExactly("n-1");
        service.markRead("n-1", new Actor("developer-1", "developer"));
        assertThat(service.list(new Actor("developer-1", "developer")).get(0).read()).isTrue();
    }

    @Test
    void marksAllNotificationsReadAndPersistsAcrossReload() throws Exception {
        Path state = Files.createTempDirectory("skill-center-notifications").resolve("state.json");
        GovernanceStore store = new GovernanceStore(state, List.of());
        NotificationService service = new NotificationService(store);
        Actor actor = new Actor("developer-1", "developer");
        store.addNotification(new NotificationRecord("n-1", actor.userId(), "system", "系统通知",
                "详情", "bell", Instant.parse("2026-08-18T10:00:00Z"), false, null));
        store.addNotification(new NotificationRecord("n-2", actor.userId(), "system", "系统通知 2",
                "详情", "bell", Instant.parse("2026-08-18T11:00:00Z"), false, null));

        service.markAllRead(actor);
        GovernanceStore reloaded = new GovernanceStore(state, List.of());
        assertThat(reloaded.snapshot().notifications()).allMatch(NotificationRecord::read);
    }
}
