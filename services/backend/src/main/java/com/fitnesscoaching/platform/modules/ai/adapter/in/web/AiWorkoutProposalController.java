package com.fitnesscoaching.platform.modules.ai.adapter.in.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.common.exception.ErrorResponse;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptRecommendationApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptRecommendationApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AiRecommendationDetailApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectRecommendationApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectRecommendationApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.UpdateRecommendationDraftApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.UpdateRecommendationDraftApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutProposalApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutProposalApiResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.AcceptRecommendationCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.AcceptRecommendationResult;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.GenerateProposalCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.RejectRecommendationCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.RejectRecommendationResult;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.UpdateDraftCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.UpdateDraftResult;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase.WorkoutProposalResult;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanFailure;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/ai", "/ai"})
public class AiWorkoutProposalController {

    private final AiWorkoutProposalUseCase proposalUseCase;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AiWorkoutProposalController(AiWorkoutProposalUseCase proposalUseCase, ObjectMapper objectMapper, Clock clock) {
        this.proposalUseCase = proposalUseCase;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @ExceptionHandler(WorkoutPlanFailure.class)
    public ResponseEntity<ErrorResponse> handleWorkoutPlanFailure(WorkoutPlanFailure failure) {
        return ResponseEntity.status(failure.error().status()).body(ErrorResponse.of(
                failure.error().name(),
                failure.getMessage() != null ? failure.getMessage() : "Workout plan operation could not be completed",
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

    @PostMapping("/workout-proposals")
    public ResponseEntity<WorkoutProposalApiResponse> generateWorkoutProposal(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody WorkoutProposalApiRequest request
    ) {
        UUID actorId = extractActor(jwt);
        if (request == null || request.commandKey() == null || request.commandKey().isBlank()) {
            throw new AiRunValidationException("commandKey is required for workout proposal generation.");
        }

        WorkoutProposalResult result = proposalUseCase.generateProposal(new GenerateProposalCommand(
                actorId,
                request.studentId(),
                request.commandKey(),
                request.preferences()
        ));

        JsonNode payload = parseJson(result.structuredPayload());

        return ResponseEntity.ok(new WorkoutProposalApiResponse(
                result.recommendationId(),
                result.runId(),
                result.status(),
                result.decisionAuthority(),
                result.title(),
                result.explanation(),
                result.expiresAt(),
                result.createdAt(),
                payload
        ));
    }

    @GetMapping("/recommendations/{recommendationId}")
    public ResponseEntity<AiRecommendationDetailApiResponse> getRecommendationDetail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID recommendationId
    ) {
        UUID actorId = extractActor(jwt);
        AiRecommendation rec = proposalUseCase.getRecommendation(actorId, recommendationId);
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

    @PutMapping("/recommendations/{recommendationId}/draft")
    public ResponseEntity<UpdateRecommendationDraftApiResponse> updateDraft(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID recommendationId,
            @RequestBody UpdateRecommendationDraftApiRequest request
    ) {
        UUID actorId = extractActor(jwt);
        if (request == null || request.expectedRevision() == null) {
            throw new AiRunValidationException("expectedRevision is required to update recommendation draft.");
        }
        if (request.structuredPayload() == null) {
            throw new AiRunValidationException("structuredPayload is required to update recommendation draft.");
        }

        String payloadString;
        try {
            payloadString = objectMapper.writeValueAsString(request.structuredPayload());
        } catch (Exception e) {
            throw new AiRunValidationException("Invalid structuredPayload JSON.");
        }

        UpdateDraftResult result = proposalUseCase.updateDraft(new UpdateDraftCommand(
                actorId,
                recommendationId,
                request.expectedRevision(),
                request.title(),
                request.explanation(),
                payloadString
        ));

        return ResponseEntity.ok(new UpdateRecommendationDraftApiResponse(
                result.recommendationId(),
                result.revision(),
                result.isCustomized(),
                result.status(),
                result.title(),
                result.explanation(),
                result.updatedAt(),
                parseJson(result.structuredPayload()),
                parseJson(result.originalPayload())
        ));
    }

    @PostMapping("/recommendations/{recommendationId}/accept")
    public ResponseEntity<AcceptRecommendationApiResponse> acceptRecommendation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID recommendationId,
            @RequestBody AcceptRecommendationApiRequest request
    ) {
        UUID actorId = extractActor(jwt);
        if (request == null || request.commandKey() == null || request.commandKey().isBlank()) {
            throw new AiRunValidationException("commandKey is required for accept recommendation.");
        }

        AcceptRecommendationResult result = proposalUseCase.acceptRecommendation(new AcceptRecommendationCommand(
                actorId,
                recommendationId,
                request.commandKey(),
                request.decisionNote(),
                request.expectedRevision()
        ));

        return ResponseEntity.ok(new AcceptRecommendationApiResponse(
                result.recommendationId(),
                result.status(),
                result.appliedBy(),
                result.resultingEntityType(),
                result.resultingEntityId(),
                result.appliedAt(),
                result.replayed()
        ));
    }

    @PostMapping("/recommendations/{recommendationId}/reject")
    public ResponseEntity<RejectRecommendationApiResponse> rejectRecommendation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID recommendationId,
            @RequestBody(required = false) RejectRecommendationApiRequest request
    ) {
        UUID actorId = extractActor(jwt);
        String reason = request != null ? request.reason() : null;

        RejectRecommendationResult result = proposalUseCase.rejectRecommendation(new RejectRecommendationCommand(
                actorId,
                recommendationId,
                reason
        ));

        return ResponseEntity.ok(new RejectRecommendationApiResponse(
                result.recommendationId(),
                result.status(),
                result.decidedBy(),
                result.decidedAt(),
                result.decisionNote()
        ));
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
