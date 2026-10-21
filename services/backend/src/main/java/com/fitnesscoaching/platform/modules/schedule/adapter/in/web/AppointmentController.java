package com.fitnesscoaching.platform.modules.schedule.adapter.in.web;

import com.fitnesscoaching.platform.modules.schedule.application.service.AppointmentService;
import com.fitnesscoaching.platform.modules.schedule.application.service.AppointmentService.LocalInterval;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.UUID;

@RestController
public class AppointmentController {
    private final AppointmentService service;

    public AppointmentController(AppointmentService service) { this.service = service; }

    @PostMapping("/api/v1/students/{studentId}/appointment-requests")
    public ResponseEntity<AppointmentService.CommandResult> request(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId, @Valid @RequestBody CreateRequest input) {
        var interval = new LocalInterval(input.localStart(), input.localEnd(), input.timezone(),
                input.startUtcOffset(), input.endUtcOffset());
        return ResponseEntity.status(201).body(service.request(new AppointmentService.RequestCommand(
                UUID.fromString(jwt.getSubject()), studentId, input.plannedWorkoutId(), interval, input.commandKey())));
    }

    @GetMapping("/api/v1/appointment-requests/{requestId}")
    public AppointmentService.RequestView detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID requestId) {
        return service.detail(UUID.fromString(jwt.getSubject()), requestId);
    }

    @GetMapping("/api/v1/appointment-requests/me")
    public AppointmentService.Page<AppointmentService.RequestView> requests(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listRequests(UUID.fromString(jwt.getSubject()), status, page, size);
    }

    @GetMapping("/api/v1/appointments/me")
    public AppointmentService.Page<AppointmentService.AppointmentView> appointments(@AuthenticationPrincipal Jwt jwt,
            @RequestParam Instant from, @RequestParam Instant until, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listAppointments(UUID.fromString(jwt.getSubject()), from, until, page, size);
    }

    @GetMapping("/api/v1/appointments/{appointmentId}")
    public AppointmentService.AppointmentView appointment(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId) {
        return service.appointmentDetail(UUID.fromString(jwt.getSubject()), appointmentId);
    }

    @GetMapping("/api/v1/appointments/{appointmentId}/history")
    public AppointmentService.Page<AppointmentService.EventView> history(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.history(UUID.fromString(jwt.getSubject()), appointmentId, page, size);
    }

    @PostMapping("/api/v1/appointment-requests/{requestId}/decisions")
    public AppointmentService.CommandResult decide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID requestId,
            @Valid @RequestBody DecisionRequest input) {
        return service.decide(new AppointmentService.DecisionCommand(UUID.fromString(jwt.getSubject()),
                requestId, input.decision(), input.expectedRequestVersion(), input.commandKey()));
    }

    @PostMapping("/api/v1/appointments/{appointmentId}/cancel")
    public AppointmentService.AppointmentCommandResult cancel(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId, @Valid @RequestBody CancelRequest input) {
        return service.cancel(new AppointmentService.CancelCommand(UUID.fromString(jwt.getSubject()),
                appointmentId, input.expectedAppointmentVersion(), input.reason(), input.commandKey()));
    }

    @PostMapping("/api/v1/appointments/{appointmentId}/reschedule-requests")
    public ResponseEntity<AppointmentService.CommandResult> requestReschedule(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID appointmentId, @Valid @RequestBody RescheduleRequest input) {
        var interval = new LocalInterval(input.localStart(), input.localEnd(), input.timezone(),
                input.startUtcOffset(), input.endUtcOffset());
        return ResponseEntity.status(201).body(service.requestReschedule(new AppointmentService.RescheduleCommand(
                UUID.fromString(jwt.getSubject()), appointmentId, input.expectedAppointmentVersion(),
                interval, input.reason(), input.commandKey())));
    }

    @PostMapping("/api/v1/appointment-reschedule-requests/{requestId}/decisions")
    public AppointmentService.CommandResult decideReschedule(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID requestId, @Valid @RequestBody RescheduleDecision input) {
        return service.decideReschedule(new AppointmentService.RescheduleDecisionCommand(
                UUID.fromString(jwt.getSubject()), requestId, input.decision(),
                input.expectedRequestVersion(), input.expectedAppointmentVersion(), input.commandKey()));
    }

    public record CreateRequest(@NotNull LocalDateTime localStart, @NotNull LocalDateTime localEnd,
                                @NotBlank String timezone, @NotBlank String startUtcOffset,
                                @NotBlank String endUtcOffset, UUID plannedWorkoutId, @NotNull UUID commandKey) {}
    public record DecisionRequest(@NotBlank String decision, @NotNull Long expectedRequestVersion,
                                  @NotNull UUID commandKey) {}
    public record CancelRequest(@NotNull Long expectedAppointmentVersion, @NotBlank String reason,
                                @NotNull UUID commandKey) {}
    public record RescheduleRequest(@NotNull Long expectedAppointmentVersion, @NotNull LocalDateTime localStart,
                                    @NotNull LocalDateTime localEnd, @NotBlank String timezone,
                                    @NotBlank String startUtcOffset, @NotBlank String endUtcOffset,
                                    String reason, @NotNull UUID commandKey) {}
    public record RescheduleDecision(@NotBlank String decision, @NotNull Long expectedRequestVersion,
                                     @NotNull Long expectedAppointmentVersion, @NotNull UUID commandKey) {}
}
