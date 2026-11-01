package com.fitnesscoaching.platform.modules.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications/me/reminder-preferences")
public class ReminderPreferenceController {
    private final ReminderPreferenceService service;
    public ReminderPreferenceController(ReminderPreferenceService service) { this.service = service; }

    @GetMapping
    public List<ReminderPreferenceService.Preference> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(UUID.fromString(jwt.getSubject()));
    }

    @PutMapping("/{type}")
    public ReminderPreferenceService.Preference set(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String type, @Valid @RequestBody SetBody body) {
        return service.set(UUID.fromString(jwt.getSubject()), type, body.enabled());
    }

    public record SetBody(@NotNull Boolean enabled) {}
}
