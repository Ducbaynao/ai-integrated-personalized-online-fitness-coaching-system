package com.fitnesscoaching.platform.modules.coaching.adapter.in.web;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.Permission;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.*;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/coaching")
public class CoachingController {
    private final CoachingLifecycleUseCase lifecycle;
    private final CoachingSharingUseCase sharing;

    public CoachingController(CoachingLifecycleUseCase lifecycle, CoachingSharingUseCase sharing) {
        this.lifecycle = lifecycle;
        this.sharing = sharing;
    }

    public record InitiateRequest(@NotNull UUID counterpartyId, @NotNull UUID commandKey) {}
    public record ActionRequest(@NotNull @Min(0) Long expectedVersion, @NotNull UUID commandKey,
                                @Size(max = 500) String reason) {}
    public record ResumeActionRequest(@NotNull @Min(0) Long expectedRelationshipVersion,
                                      @NotNull @Min(0) Long expectedRequestVersion, @NotNull UUID commandKey) {}
    public record SharingGrantRequest(@NotNull DataScope dataScope,
                                      @NotNull SharingDecision decision,
                                      @NotNull DataAccessLevel accessLevel,
                                      Instant historyFrom,
                                      Instant historyUntil,
                                      Instant validUntil,
                                      @Min(0) Long expectedPermissionVersion,
                                      @NotNull UUID commandKey) {}
    public record SharingRevokeRequest(@NotNull @Min(0) Long expectedPermissionVersion,
                                       @Size(max = 500) String reason,
                                       @NotNull UUID commandKey) {}
    public record RelationshipDto(UUID id, UUID studentId, UUID trainerId, String status,
                                  String direction, UUID requestedBy, Instant requestedAt,
                                  Instant acceptedAt, Instant startedAt, Instant endedAt, long version) {
        static RelationshipDto from(Relationship value, UUID actor) {
            if (value == null) { return null; }
            return new RelationshipDto(value.id(), value.studentId(), value.trainerId(), value.status(),
                    value.directionFor(actor), value.requestedBy(), value.requestedAt(), value.acceptedAt(),
                    value.startedAt(), value.endedAt(), value.version());
        }
    }
    public record ResumeDto(UUID id, UUID relationshipId, UUID requestedBy, String status,
                            long version, Instant requestedAt, UUID decidedBy, Instant decidedAt) {
        static ResumeDto from(Resume value) {
            return value == null ? null : new ResumeDto(value.id(), value.relationshipId(), value.requestedBy(),
                    value.status(), value.version(), value.requestedAt(), value.decidedBy(), value.decidedAt());
        }
    }
    public record PeriodDto(UUID id, String mode, UUID relationshipId, UUID trainerId,
                            Instant startedAt, Instant endedAt) {
        static PeriodDto from(Period value) {
            return value == null ? null : new PeriodDto(value.id(), value.mode(), value.relationshipId(),
                    value.trainerId(), value.startedAt(), value.endedAt());
        }
    }
    public record OutcomeDto(RelationshipDto relationship, ResumeDto resume, PeriodDto currentPeriod) {
        static OutcomeDto from(Outcome value, UUID actor) {
            return new OutcomeDto(RelationshipDto.from(value.relationship(), actor),
                    ResumeDto.from(value.resume()), PeriodDto.from(value.currentPeriod()));
        }
    }
    public record HistoryDto(UUID id, UUID relationshipId, String fromStatus, String toStatus,
                             UUID changedBy, String reason, Instant changedAt) {
        static HistoryDto from(History value) {
            return new HistoryDto(value.id(), value.relationshipId(), value.fromStatus(),
                    value.toStatus(), value.changedBy(), value.reason(), value.changedAt());
        }
    }
    public record SharingPermissionDto(UUID id, UUID relationshipId, UUID studentId, UUID trainerId,
                                       DataScope dataScope, SharingDecision decision,
                                       DataAccessLevel accessLevel, Instant historyFrom,
                                       Instant historyUntil, Instant validFrom, Instant validUntil,
                                       UUID grantedBy, Instant revokedAt, String revokeReason,
                                       long version, Instant createdAt) {
        static SharingPermissionDto from(Permission value) {
            return new SharingPermissionDto(value.id(), value.relationshipId(), value.studentId(),
                    value.trainerId(), value.dataScope(), value.decision(), value.accessLevel(),
                    value.historyFrom(), value.historyUntil(), value.validFrom(), value.validUntil(),
                    value.grantedBy(), value.revokedAt(), value.revokeReason(), value.version(),
                    value.createdAt());
        }
    }
    public record PageDto<T>(List<T> items, int page, int size) {}

    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    @GetMapping("/relationships/me/current")
    public OutcomeDto current(@AuthenticationPrincipal Jwt jwt) {
        UUID actor = actor(jwt);
        return OutcomeDto.from(lifecycle.current(actor), actor);
    }

    @GetMapping("/relationships/me/pending")
    public PageDto<RelationshipDto> pending(@AuthenticationPrincipal Jwt jwt,
                                            @RequestParam(defaultValue = "INCOMING") String direction,
                                            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        UUID actor = actor(jwt);
        return new PageDto<>(lifecycle.pending(actor, direction, page, size).stream()
                .map(value -> RelationshipDto.from(value, actor)).toList(), page, size);
    }

    @GetMapping("/relationships/{relationshipId}")
    public OutcomeDto detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID relationshipId) {
        UUID actor = actor(jwt);
        return OutcomeDto.from(lifecycle.detail(actor, relationshipId), actor);
    }

    @GetMapping("/relationships/{relationshipId}/history")
    public PageDto<HistoryDto> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID relationshipId,
                                       @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        UUID actor = actor(jwt);
        return new PageDto<>(lifecycle.history(actor, relationshipId, page, size).stream()
                .map(HistoryDto::from).toList(), page, size);
    }

    @GetMapping("/periods/me")
    public PageDto<PeriodDto> periods(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        UUID actor = actor(jwt);
        return new PageDto<>(lifecycle.periods(actor, page, size).stream().map(PeriodDto::from).toList(), page, size);
    }

    @PostMapping("/relationships/requests")
    public ResponseEntity<OutcomeDto> request(@AuthenticationPrincipal Jwt jwt,
                                               @Valid @RequestBody InitiateRequest body) {
        UUID actor = actor(jwt);
        Outcome result = lifecycle.initiate(actor, actor, body.counterpartyId(), body.commandKey());
        return ResponseEntity.created(URI.create("/api/v1/coaching/relationships/" + result.relationship().id()))
                .body(OutcomeDto.from(result, actor));
    }

    @PostMapping("/relationships/invitations")
    public ResponseEntity<OutcomeDto> invite(@AuthenticationPrincipal Jwt jwt,
                                              @Valid @RequestBody InitiateRequest body) {
        UUID actor = actor(jwt);
        Outcome result = lifecycle.initiate(actor, body.counterpartyId(), actor, body.commandKey());
        return ResponseEntity.created(URI.create("/api/v1/coaching/relationships/" + result.relationship().id()))
                .body(OutcomeDto.from(result, actor));
    }

    @PostMapping("/relationships/{relationshipId}/{action:accept|reject|cancel|pause|end}")
    public OutcomeDto relationshipAction(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID relationshipId,
                                         @PathVariable String action, @Valid @RequestBody ActionRequest body) {
        UUID actor = actor(jwt);
        return OutcomeDto.from(lifecycle.relationshipAction(actor, relationshipId, action.toUpperCase(),
                body.expectedVersion(), body.reason(), body.commandKey()), actor);
    }

    @PostMapping("/relationships/{relationshipId}/resume-requests")
    public ResponseEntity<OutcomeDto> requestResume(@AuthenticationPrincipal Jwt jwt,
                                                     @PathVariable UUID relationshipId,
                                                     @Valid @RequestBody ActionRequest body) {
        UUID actor = actor(jwt);
        Outcome result = lifecycle.createResume(actor, relationshipId, body.expectedVersion(),
                body.reason(), body.commandKey());
        return ResponseEntity.created(URI.create("/api/v1/coaching/relationships/" + relationshipId
                        + "/resume-requests/" + result.resume().id())).body(OutcomeDto.from(result, actor));
    }

    @GetMapping("/relationships/{relationshipId}/resume-requests")
    public PageDto<ResumeDto> resumeHistory(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable UUID relationshipId,
                                             @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return new PageDto<>(lifecycle.resumeHistory(actor(jwt), relationshipId, page, size).stream()
                .map(ResumeDto::from).toList(), page, size);
    }

    @PostMapping("/relationships/{relationshipId}/resume-requests/{resumeRequestId}/{action:accept|reject|cancel}")
    public OutcomeDto resumeAction(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID relationshipId,
                                   @PathVariable UUID resumeRequestId, @PathVariable String action,
                                   @Valid @RequestBody ResumeActionRequest body) {
        UUID actor = actor(jwt);
        return OutcomeDto.from(lifecycle.resumeAction(actor, relationshipId, resumeRequestId,
                action.toUpperCase(), body.expectedRelationshipVersion(), body.expectedRequestVersion(),
                body.commandKey()), actor);
    }

    @GetMapping("/relationships/{relationshipId}/sharing-permissions")
    public PageDto<SharingPermissionDto> sharingPermissions(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID relationshipId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return new PageDto<>(sharing.list(actor(jwt), relationshipId, page, size).stream()
                .map(SharingPermissionDto::from).toList(), page, size);
    }

    @PostMapping("/relationships/{relationshipId}/sharing-permissions")
    public SharingPermissionDto grantOrReplaceSharing(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID relationshipId,
            @Valid @RequestBody SharingGrantRequest body) {
        return SharingPermissionDto.from(sharing.grantOrReplace(actor(jwt), relationshipId,
                body.dataScope(), body.decision(), body.accessLevel(), body.historyFrom(),
                body.historyUntil(), body.validUntil(), body.expectedPermissionVersion(), body.commandKey()));
    }

    @PostMapping("/relationships/{relationshipId}/sharing-permissions/{permissionId}/revoke")
    public SharingPermissionDto revokeSharing(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID relationshipId,
            @PathVariable UUID permissionId,
            @Valid @RequestBody SharingRevokeRequest body) {
        return SharingPermissionDto.from(sharing.revoke(actor(jwt), relationshipId, permissionId,
                body.expectedPermissionVersion(), body.reason(), body.commandKey()));
    }
}
