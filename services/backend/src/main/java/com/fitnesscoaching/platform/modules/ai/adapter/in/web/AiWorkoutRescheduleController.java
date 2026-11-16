package com.fitnesscoaching.platform.modules.ai.adapter.in.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptRescheduleApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AiRecommendationDetailApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectRescheduleApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutRescheduleApiResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutRescheduleUseCase;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/ai", "/ai"})
public class AiWorkoutRescheduleController {

    private final AiWorkoutRescheduleUseCase rescheduleUseCase;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AiWorkoutRescheduleController(
            AiWorkoutRescheduleUseCase rescheduleUseCase,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.rescheduleUseCase = rescheduleUseCase;
        this.objectMapper = objectMapper;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @ExceptionHandler(WorkoutScheduleFailure.class)
    public ResponseEntity<ErrorResponse> handleWorkoutScheduleFailure(WorkoutScheduleFailure failure) {
        return ResponseEntity.status(failure.status()).body(ErrorResponse.of(
                failure.code(),
                failure.getMessage() != null ? failure.getMessage() : "Workout schedule operation could not be completed",
                Instant.now(clock),
                RequestIdHolder.get()
        ));
    }

    private UUID extractActor(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new AiRunAccessDeniedException("Authentication required.");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw new AiRunAccessDeniedException("Invalid authenticated user ID in token subject.");
        }
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    @PostMapping("/workout-reschedules")
    public ResponseEntity<WorkoutRescheduleApiResponse> generateWorkoutReschedule(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody WorkoutRescheduleApiRequest request
    ) {
        UUID actorId = extractActor(jwt);
        if (request == null || request.commandKey() == null || request.commandKey().isBlank()) {
            throw new AiRunValidationException("commandKey is required for workout rescheduling generation.");
        }
        if (request.occurrenceId() == null) {
            throw new AiRunValidationException("occurrenceId is required.");
        }

        WorkoutRescheduleApiResponse response = rescheduleUseCase.generateReschedule(actorId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/workout-reschedules/{recommendationId}")
    public ResponseEntity<AiRecommendationDetailApiResponse> getWorkoutRescheduleDetail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID recommendationId
    ) {
        UUID actorId = extractActor(jwt);
        AiRecommendation rec = rescheduleUseCase.getReschedule(actorId, recommendationId);
        JsonNode payload = parseJson(rec.structuredPayload());
        JsonNode origPayload = parseJson(rec.originalPayload());

        return ResponseEntity.ok(new AiRecommendationDetailApiResponse(
                rec.id(),
                rec.aiRunId(),
                rec.studentId(),
                rec.coachingPeriodId(),
                rec.recommendationType(),
                rec.title(),
                rec.explanation(),
                rec.status(),
                rec.decisionAuthority(),
                rec.targetEntityType(),
                rec.targetEntityId(),
                rec.decidedBy(),
                rec.decidedAt(),
                rec.decisionNote(),
                rec.expiresAt(),
                rec.revision(),
                rec.isCustomized(),
                rec.lastModifiedBy(),
                rec.createdAt(),
                rec.updatedAt(),
                payload,
                origPayload,
                rec.sharedWithStudent(),
                rec.sharedRevision(),
                rec.sharedAt(),
                rec.sharedBy()
        ));
    }

    @PostMapping("/workout-reschedules/{recommendationId}/accept")
    public ResponseEntity<AcceptRescheduleApiResponse> acceptWorkoutReschedule(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID recommendationId,
            @RequestBody(required = false) AcceptRescheduleApiRequest request
    ) {
        UUID actorId = extractActor(jwt);
        AcceptRescheduleApiRequest req = request != null
                ? request
                : new AcceptRescheduleApiRequest(null, null, null, null, null, null);
        AcceptRescheduleApiResponse response = rescheduleUseCase.acceptReschedule(actorId, recommendationId, req);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/workout-reschedules/{recommendationId}/reject")
    public ResponseEntity<RejectRescheduleApiResponse> rejectWorkoutReschedule(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID recommendationId,
            @RequestBody(required = false) RejectRescheduleApiRequest request
    ) {
        UUID actorId = extractActor(jwt);
        RejectRescheduleApiRequest req = request != null ? request : new RejectRescheduleApiRequest(null);
        RejectRescheduleApiResponse response = rescheduleUseCase.rejectReschedule(actorId, recommendationId, req);
        return ResponseEntity.ok(response);
    }
}
