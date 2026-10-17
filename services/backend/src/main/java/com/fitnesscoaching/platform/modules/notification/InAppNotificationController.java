package com.fitnesscoaching.platform.modules.notification;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications/me")
public class InAppNotificationController {
    private final InAppNotificationService notifications;
    public InAppNotificationController(InAppNotificationService notifications) { this.notifications = notifications; }

    @GetMapping
    public InAppNotificationService.Page list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return notifications.list(UUID.fromString(jwt.getSubject()), page, size);
    }

    @PostMapping("/{id}/read")
    public InAppNotificationService.Item read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return notifications.markRead(UUID.fromString(jwt.getSubject()), id);
    }
}
