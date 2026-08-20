package com.huawei.skillcenter.notification;

import com.huawei.skillcenter.api.ApiResponse;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/me/notifications")
public class NotificationController {
    private final NotificationService service;
    private final ActorResolver actorResolver;

    public NotificationController(NotificationService service, ActorResolver actorResolver) {
        this.service = service;
        this.actorResolver = actorResolver;
    }

    @GetMapping
    ResponseEntity<ApiResponse<NotificationPage>> list(HttpServletRequest request) {
        List<NotificationRecord> items = service.list(actorResolver.resolve(request));
        long unread = items.stream().filter(item -> !item.read()).count();
        return ok(new NotificationPage(items, unread), request);
    }

    @PutMapping("/{notificationId}/read")
    ResponseEntity<ApiResponse<NotificationRecord>> read(@PathVariable String notificationId,
                                                           HttpServletRequest request) {
        Actor actor = actorResolver.resolve(request);
        return ok(service.markRead(notificationId, actor), request);
    }

    @PostMapping("/read-all")
    ResponseEntity<ApiResponse<NotificationPage>> readAll(HttpServletRequest request) {
        List<NotificationRecord> items = service.markAllRead(actorResolver.resolve(request));
        return ok(new NotificationPage(items, 0), request);
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data, HttpServletRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(data,
                String.valueOf(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE))));
    }

    public record NotificationPage(List<NotificationRecord> items, long unreadCount) {
    }
}
