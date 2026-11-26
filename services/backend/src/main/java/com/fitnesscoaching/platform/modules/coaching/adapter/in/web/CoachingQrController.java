package com.fitnesscoaching.platform.modules.coaching.adapter.in.web;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingQrUseCase;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingQrState.*;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/coaching/qr")
public class CoachingQrController {
    private final CoachingQrUseCase qrUseCase;

    public CoachingQrController(CoachingQrUseCase qrUseCase) {
        this.qrUseCase = qrUseCase;
    }

    public record IssueCoachingQrRequest(@NotNull QrRole role) {}

    public record RotateCoachingQrRequest(
            @NotNull QrRole role,
            @NotNull @Min(1) Long expectedVersion,
            @NotNull UUID commandKey) {}

    public record ResolveCoachingQrRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{32}$") String publicCode,
            @NotNull QrRole expectedTargetRole,
            @NotNull @Min(1) @Max(1) Integer payloadVersion) {}

    public record CoachingQrIdentityDto(
            UUID qrId,
            QrRole role,
            String publicCode,
            String qrUri,
            int payloadVersion,
            String displayName,
            Instant issuedAt,
            long version) {
        static CoachingQrIdentityDto from(QrIdentity value) {
            return new CoachingQrIdentityDto(
                    value.qrId(),
                    value.role(),
                    value.publicCode(),
                    value.qrUri(),
                    value.payloadVersion(),
                    value.displayName(),
                    value.issuedAt(),
                    value.version()
            );
        }
    }

    public record CoachingQrTrainerSummaryDto(
            TrainerVerificationStatus verificationStatus,
            List<String> specialties,
            BigDecimal yearsExperience,
            boolean isAcceptingStudents) {
        static CoachingQrTrainerSummaryDto from(TrainerSummary value) {
            return value == null ? null : new CoachingQrTrainerSummaryDto(
                    value.verificationStatus(),
                    value.specialties(),
                    value.yearsExperience(),
                    value.isAcceptingStudents()
            );
        }
    }

    public record CoachingQrRelationshipStateDto(
            String status,
            UUID relationshipId,
            String direction,
            Long version,
            boolean canInitiate) {
        static CoachingQrRelationshipStateDto from(RelationshipState value) {
            return new CoachingQrRelationshipStateDto(
                    value.status(),
                    value.relationshipId(),
                    value.direction(),
                    value.version(),
                    value.canInitiate()
            );
        }
    }

    public record CoachingQrProfilePreviewDto(
            UUID counterpartyId,
            QrRole targetRole,
            String displayName,
            String avatarUrl,
            CoachingQrTrainerSummaryDto trainerSummary,
            CoachingQrRelationshipStateDto relationshipState) {
        static CoachingQrProfilePreviewDto from(QrProfilePreview value) {
            return new CoachingQrProfilePreviewDto(
                    value.counterpartyId(),
                    value.targetRole(),
                    value.displayName(),
                    value.avatarUrl(),
                    CoachingQrTrainerSummaryDto.from(value.trainerSummary()),
                    CoachingQrRelationshipStateDto.from(value.relationshipState())
            );
        }
    }

    private static UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @GetMapping("/me")
    public CoachingQrIdentityDto getMyQr(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam @NotNull QrRole role) {
        return CoachingQrIdentityDto.from(qrUseCase.getMyQr(actor(jwt), role));
    }

    @PostMapping("/me")
    public ResponseEntity<CoachingQrIdentityDto> issueMyQr(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody IssueCoachingQrRequest body) {
        IssueResult result = qrUseCase.issueMyQr(actor(jwt), body.role());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(CoachingQrIdentityDto.from(result.identity()));
    }

    @PostMapping("/me/rotate")
    public CoachingQrIdentityDto rotateMyQr(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RotateCoachingQrRequest body) {
        return CoachingQrIdentityDto.from(
                qrUseCase.rotateMyQr(actor(jwt), body.role(), body.expectedVersion(), body.commandKey()));
    }

    @PostMapping("/resolve")
    public CoachingQrProfilePreviewDto resolveQr(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ResolveCoachingQrRequest body) {
        return CoachingQrProfilePreviewDto.from(
                qrUseCase.resolveQr(actor(jwt), body.publicCode(), body.expectedTargetRole(), body.payloadVersion()));
    }
}
