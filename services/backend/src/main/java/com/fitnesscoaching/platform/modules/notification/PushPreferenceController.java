package com.fitnesscoaching.platform.modules.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications/me/push")
public class PushPreferenceController {
    private final PushPreferenceService service;
    public PushPreferenceController(PushPreferenceService service) { this.service = service; }
    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    @GetMapping("/preferences")
    public List<PushPreferenceService.Preference> preferences(@AuthenticationPrincipal Jwt jwt) {
        return service.list(actor(jwt));
    }
    @GetMapping("/deliveries/{notificationId}")
    public List<PushPreferenceService.Delivery> deliveries(@AuthenticationPrincipal Jwt jwt,
                                                           @PathVariable UUID notificationId) {
        return service.deliveries(actor(jwt), notificationId);
    }
    @PutMapping("/preferences/{eventType}")
    public PushPreferenceService.Preference preference(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String eventType, @Valid @RequestBody PreferenceBody body) {
        return service.set(actor(jwt), eventType, body.enabled(), body.timezone(),
                body.quietHoursStart(), body.quietHoursEnd());
    }
    @PutMapping("/devices/{deviceKey}")
    public ResponseEntity<Void> device(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceKey,
                                       @Valid @RequestBody DeviceBody body) {
        service.register(actor(jwt), deviceKey, body.expoPushToken());
        return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/devices/{deviceKey}")
    public ResponseEntity<Void> removeDevice(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceKey) {
        service.unregister(actor(jwt), deviceKey);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/devices/{deviceKey}/received/{notificationId}")
    public ResponseEntity<Void> received(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceKey,
                                         @PathVariable UUID notificationId) {
        service.deviceReceived(actor(jwt), deviceKey, notificationId);
        return ResponseEntity.noContent().build();
    }
    public record PreferenceBody(boolean enabled, String timezone, String quietHoursStart, String quietHoursEnd) {}
    public record DeviceBody(@NotNull String expoPushToken) {}
}
