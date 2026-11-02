package com.fitnesscoaching.platform.modules.ai.adapter.in.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AiRecommendationDetailApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutAdjustmentUseCase;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanFailure;
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
public class AiWorkoutAdjustmentController {

    private final AiWorkoutAdjustmentUseCase adjustmentUseCase;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AiWorkoutAdjustmentController(AiWorkoutAdjustmentUseCase adjustmentUseCase, ObjectMapper objectMapper, Clock clock) {
        this.adjustmentUseCase = adjustmentUseCase;
        this.objectMapper = objectMapper;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @ExceptionHandler(WorkoutPlanFailure.class)
    public ResponseEntity<ErrorResponse> handleWorkoutPlanFailure(WorkoutPlanFailure failure) {
        return ResponseEntity.status(failure.error().status()).body(ErrorResponse.of(
                failure.error().name(),
                failure.getMessage() != null ? failure.getMessage() : "Workout operation could not be completed",
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

    @PostMapping("/workout-adjustments")
    public ResponseEntity<WorkoutAdjustmentApiResponse> generateWorkoutAdjustment(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody WorkoutAdjustmentApiRequest request
    ) {
        UUID actorId = extractActor(jwt);
        if (request == null || request.commandKey() == null || request.commandKey().isBlank()) {
            throw new AiRunValidationException("commandKey is required for workout adjustment generation.");
        }
        if (request.scope() == null || request.target() == null) {
            throw new AiRunValidationException("scope and target reference are required.");
        }

        WorkoutAdjustmentApiResponse response = adjustmentUseCase.generateAdjustment(actorId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/workout-adjustments/{recommendationId}")
    public ResponseEntity<AiRecommendationDetailApiResponse> getWorkoutAdjustmentDetail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID recommendationId
    ) {
        UUID actorId = extractActor(jwt);
        AiRecommendation rec = adjustmentUseCase.getAdjustment(actorId, recommendationId);
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
                origPayload
        ));
    }

    @PostMapping("/workout-adjustments/{recommendationId}/accept")
    public ResponseEntity<AcceptAdjustmentApiResponse> acceptWorkoutAdjustment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID recommendationId,
            @RequestBody(required = false) AcceptAdjustmentApiRequest request
    ) {
        UUID actorId = extractActor(jwt);
        AcceptAdjustmentApiRequest req = request != null ? request : new AcceptAdjustmentApiRequest(null, null, null, null);
        AcceptAdjustmentApiResponse response = adjustmentUseCase.acceptAdjustment(actorId, recommendationId, req);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/workout-adjustments/{recommendationId}/reject")
    public ResponseEntity<RejectAdjustmentApiResponse> rejectWorkoutAdjustment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID recommendationId,
            @RequestBody(required = false) RejectAdjustmentApiRequest request
    ) {
        UUID actorId = extractActor(jwt);
        RejectAdjustmentApiRequest req = request != null ? request : new RejectAdjustmentApiRequest(null);
        RejectAdjustmentApiResponse response = adjustmentUseCase.rejectAdjustment(actorId, recommendationId, req);
        return ResponseEntity.ok(response);
    }
}
