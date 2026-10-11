package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
public class WorkoutScheduleCalendarController {
    private final WorkoutScheduleCalendarQuery calendar;
    public WorkoutScheduleCalendarController(WorkoutScheduleCalendarQuery calendar) { this.calendar = calendar; }

    @GetMapping("/api/v1/planned-workouts")
    public WorkoutScheduleCalendarQuery.Page listMine(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam("until") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return calendar.listMine(UUID.fromString(jwt.getSubject()), from, until, page, size);
    }

    @GetMapping("/api/v1/students/{studentId}/planned-workouts")
    public WorkoutScheduleCalendarQuery.Page listForTrainer(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID studentId,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam("until") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant until,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return calendar.listForTrainer(UUID.fromString(jwt.getSubject()), studentId, from, until, page, size);
    }
}
