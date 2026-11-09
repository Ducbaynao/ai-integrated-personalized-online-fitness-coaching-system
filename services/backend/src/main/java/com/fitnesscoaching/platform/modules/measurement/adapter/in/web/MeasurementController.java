package com.fitnesscoaching.platform.modules.measurement.adapter.in.web;

import com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto.CorrectMeasurementRequest;
import com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto.MeasurementPageResponse;
import com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto.MeasurementResponse;
import com.fitnesscoaching.platform.modules.measurement.adapter.in.web.dto.RecordMeasurementRequest;
import com.fitnesscoaching.platform.modules.measurement.application.service.MeasurementService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/measurements")
public class MeasurementController {

    private final MeasurementService measurementService;

    public MeasurementController(MeasurementService measurementService) {
        this.measurementService = measurementService;
    }

    @PostMapping
    public ResponseEntity<MeasurementResponse> recordMeasurement(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RecordMeasurementRequest request
    ) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        MeasurementResponse response = measurementService.recordMeasurement(actorId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/correction")
    public ResponseEntity<MeasurementResponse> correctMeasurement(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @Valid @RequestBody CorrectMeasurementRequest request
    ) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        MeasurementResponse response = measurementService.correctMeasurement(actorId, id, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<MeasurementPageResponse> getMeasurements(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID studentId,
            @RequestParam(required = false) String metricCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false, defaultValue = "Asia/Ho_Chi_Minh") String timezone,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size,
            @RequestParam(required = false, defaultValue = "DESC") String sort
    ) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        ZoneId zone = ZoneId.of(timezone != null ? timezone : "Asia/Ho_Chi_Minh");
        MeasurementPageResponse response = measurementService.getHistory(
                actorId, studentId, metricCode, fromDate, toDate, zone, page, size, sort
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MeasurementResponse> getMeasurementById(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id
    ) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        MeasurementResponse response = measurementService.getMeasurementById(actorId, id);
        return ResponseEntity.ok(response);
    }
}
