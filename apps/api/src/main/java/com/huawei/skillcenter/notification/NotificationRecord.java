package com.huawei.skillcenter.notification;

import java.time.Instant;

public record NotificationRecord(
        String notificationId,
        String userId,
        String type,
        String title,
        String detail,
        String icon,
        Instant createdAt,
        boolean read,
        Instant readAt
) {
    public NotificationRecord {
        require(notificationId, "notificationId");
        require(userId, "userId");
        require(type, "type");
        require(title, "title");
        require(detail, "detail");
        icon = icon == null || icon.isBlank() ? "bell" : icon.trim();
        createdAt = createdAt == null ? Instant.now() : createdAt;
        if (read && readAt == null) {
            readAt = createdAt;
        }
        if (!read) {
            readAt = null;
        }
    }

    public NotificationRecord markRead(Instant at) {
        return new NotificationRecord(notificationId, userId, type, title, detail, icon,
                createdAt, true, at == null ? Instant.now() : at);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
