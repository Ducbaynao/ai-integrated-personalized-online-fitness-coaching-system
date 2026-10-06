package com.fitnesscoaching.platform.modules.coaching.adapter.in.web;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingMobileReadUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.Permission;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.PermissionSummary;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.PermissionSummaryItem;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.*;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import com.fitnesscoaching.platform.modules.trainer.application.model.TrainerDirectoryItem;
import com.fitnesscoaching.platform.modules.user.application.model.UserDisplaySummary;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserDirectoryQuery;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/coaching")
public class CoachingController {
    private final CoachingLifecycleUseCase lifecycle;
    private final CoachingSharingUseCase sharing;
    private final CoachingMobileReadUseCase mobileRead;
    private final UserDirectoryQuery users;

    public CoachingController(CoachingLifecycleUseCase lifecycle, CoachingSharingUseCase sharing,
                              CoachingMobileReadUseCase mobileRead, UserDirectoryQuery users) {
        this.lifecycle = lifecycle;
        this.sharing = sharing;
        this.mobileRead = mobileRead;
        this.users = users;
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
    public record ParticipantDto(UUID userId, String displayName) {}
    public record RelationshipDto(UUID id, UUID studentId, UUID trainerId, ParticipantDto counterparty,
                                  String status,
                                  String direction, UUID requestedBy, Instant requestedAt,
                                  Instant acceptedAt, Instant startedAt, Instant endedAt, long version) {}
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
    public record TrainerDirectoryDto(UUID trainerId, String displayName) {
        static TrainerDirectoryDto from(TrainerDirectoryItem value) {
            return new TrainerDirectoryDto(value.trainerId(), value.displayName());
        }
    }
    public record StudentLookupDto(UUID studentId, String displayName) {}
    public record PermissionSummaryItemDto(DataScope dataScope, String state,
                                           SharingDecision decision, DataAccessLevel accessLevel,
                                           UUID permissionId, Long version,
                                           Instant historyFrom, Instant historyUntil,
                                           Instant validFrom, Instant validUntil) {
        static PermissionSummaryItemDto from(PermissionSummaryItem value) {
            return new PermissionSummaryItemDto(value.dataScope(), value.state().name(), value.decision(),
                    value.accessLevel(), value.permissionId(), value.version(), value.historyFrom(),
                    value.historyUntil(), value.validFrom(), value.validUntil());
        }
    }
    public record PermissionSummaryDto(UUID relationshipId, String relationshipStatus,
                                       Instant evaluatedAt, List<PermissionSummaryItemDto> items) {
        static PermissionSummaryDto from(PermissionSummary value) {
            return new PermissionSummaryDto(value.relationshipId(), value.relationshipStatus(),
                    value.evaluatedAt(), value.items().stream().map(PermissionSummaryItemDto::from).toList());
        }
    }
    public record PageDto<T>(List<T> items, int page, int size) {}

    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    private OutcomeDto outcomeDto(Outcome value, UUID actor) {
        return new OutcomeDto(relationshipDto(value.relationship(), actor),
                ResumeDto.from(value.resume()), PeriodDto.from(value.currentPeriod()));
    }

    private RelationshipDto relationshipDto(Relationship value, UUID actor) {
        if (value == null) { return null; }
        UUID counterpartyId = value.studentId().equals(actor) ? value.trainerId() : value.studentId();
        return relationshipDto(value, actor, requireSummaries(List.of(counterpartyId)).get(counterpartyId));
    }

    private List<RelationshipDto> relationshipDtos(List<Relationship> values, UUID actor) {
        Collection<UUID> ids = values.stream()
                .map(value -> value.studentId().equals(actor) ? value.trainerId() : value.studentId())
                .toList();
        Map<UUID, UserDisplaySummary> summaries = requireSummaries(ids);
        return values.stream().map(value -> {
            UUID counterpartyId = value.studentId().equals(actor) ? value.trainerId() : value.studentId();
            return relationshipDto(value, actor, summaries.get(counterpartyId));
        }).toList();
    }

    private RelationshipDto relationshipDto(Relationship value, UUID actor, UserDisplaySummary counterparty) {
        return new RelationshipDto(value.id(), value.studentId(), value.trainerId(),
                new ParticipantDto(counterparty.userId(), counterparty.displayName()), value.status(),
                value.directionFor(actor), value.requestedBy(), value.requestedAt(), value.acceptedAt(),
                value.startedAt(), value.endedAt(), value.version());
    }

    private Map<UUID, UserDisplaySummary> requireSummaries(Collection<UUID> ids) {
        Map<UUID, UserDisplaySummary> summaries = users.findDisplaySummaries(ids);
        if (summaries.size() != ids.stream().distinct().count()) {
            throw new IllegalStateException("Coaching participant display summary is unavailable");
        }
        return summaries;
    }

    @GetMapping("/relationships/me/current")
    public OutcomeDto current(@AuthenticationPrincipal Jwt jwt) {
        UUID actor = actor(jwt);
        return outcomeDto(lifecycle.current(actor), actor);
    }

    @GetMapping("/trainers")
    public PageDto<TrainerDirectoryDto> trainers(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "") @Size(max = 160) String query,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = mobileRead.discoverTrainers(actor(jwt), query, page, size);
        return new PageDto<>(result.items().stream().map(TrainerDirectoryDto::from).toList(),
                result.page(), result.size());
    }

    @GetMapping("/students/lookup")
    public StudentLookupDto lookupStudent(@AuthenticationPrincipal Jwt jwt,
                                          @RequestParam @Size(max = 320) String email) {
        UserDisplaySummary result = mobileRead.lookupStudent(actor(jwt), email);
        return new StudentLookupDto(result.userId(), result.displayName());
    }

    @GetMapping("/relationships/me")
    public PageDto<RelationshipDto> relationships(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        UUID actor = actor(jwt);
        return new PageDto<>(relationshipDtos(mobileRead.relationships(actor, page, size), actor), page, size);
    }

    @GetMapping("/relationships/me/pending")
    public PageDto<RelationshipDto> pending(@AuthenticationPrincipal Jwt jwt,
                                            @RequestParam(defaultValue = "INCOMING") String direction,
                                            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        UUID actor = actor(jwt);
        return new PageDto<>(relationshipDtos(lifecycle.pending(actor, direction, page, size), actor), page, size);
    }

    @GetMapping("/relationships/{relationshipId}")
    public OutcomeDto detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID relationshipId) {
        UUID actor = actor(jwt);
        return outcomeDto(lifecycle.detail(actor, relationshipId), actor);
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
                .body(outcomeDto(result, actor));
    }

    @PostMapping("/relationships/invitations")
    public ResponseEntity<OutcomeDto> invite(@AuthenticationPrincipal Jwt jwt,
                                              @Valid @RequestBody InitiateRequest body) {
        UUID actor = actor(jwt);
        Outcome result = lifecycle.initiate(actor, body.counterpartyId(), actor, body.commandKey());
        return ResponseEntity.created(URI.create("/api/v1/coaching/relationships/" + result.relationship().id()))
                .body(outcomeDto(result, actor));
    }

    @PostMapping("/relationships/{relationshipId}/{action:accept|reject|cancel|pause|end}")
    public OutcomeDto relationshipAction(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID relationshipId,
                                         @PathVariable String action, @Valid @RequestBody ActionRequest body) {
        UUID actor = actor(jwt);
        return outcomeDto(lifecycle.relationshipAction(actor, relationshipId, action.toUpperCase(),
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
                        + "/resume-requests/" + result.resume().id())).body(outcomeDto(result, actor));
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
        return outcomeDto(lifecycle.resumeAction(actor, relationshipId, resumeRequestId,
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

    @GetMapping("/relationships/{relationshipId}/sharing-permissions/summary")
    public PermissionSummaryDto sharingPermissionSummary(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID relationshipId) {
        return PermissionSummaryDto.from(sharing.summary(actor(jwt), relationshipId));
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
