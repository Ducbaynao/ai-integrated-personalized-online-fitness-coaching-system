package com.fitnesscoaching.platform.modules.schedule.application.service;

import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleProposalUseCase;
import com.fitnesscoaching.platform.modules.schedule.application.port.out.ScheduleProposalStore;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.ConfirmBatch;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ScheduleProposalService implements ScheduleProposalUseCase {
    private final ScheduleProposalStore store;
    private final WorkoutScheduleBatchUseCase workout;
    private final CoachingAuthorityQuery coaching;
    private final StudentCapabilityQuery students;
    private final UserAccountStatusQuery accounts;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public ScheduleProposalService(ScheduleProposalStore store, WorkoutScheduleBatchUseCase workout,
                                   CoachingAuthorityQuery coaching, StudentCapabilityQuery students,
                                   UserAccountStatusQuery accounts, AuditService audit, ObjectMapper mapper) {
        this.store = store; this.workout = workout; this.coaching = coaching; this.students = students;
        this.accounts = accounts; this.audit = audit; this.mapper = mapper;
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public CommandResult create(Create c) {
        if (c == null || c.trainerId() == null || c.studentId() == null || c.planId() == null
                || c.sourcePlanVersionId() == null) throw fail("WORKOUT_SCHEDULE_VALIDATION_FAILED", 400);
        key(c.commandKey());
        if (!c.studentId().equals(workout.studentForPlan(c.planId())))
            throw fail("WORKOUT_PLAN_NOT_FOUND", 404);
        // The Student boundary is always first; Coaching locks Trainer/period/permission next.
        workout.lockStudentAndActor(c.studentId(), c.trainerId());
        AuthorityDecision authority = trainerAuthority(c.trainerId(), c.studentId(), DataAccessLevel.CONTRIBUTE, false);
        String hash = hash("PROPOSE", c.studentId(), c.planId(), c.sourcePlanVersionId(),
                c.expectedPlanAggregateVersion(), c.weekAnchorDate(), c.timezone(), c.items());
        var prior = store.lockReceipt(c.trainerId(), c.commandKey());
        if (prior.isPresent()) return replay(prior.get(), "PROPOSE", hash, CommandResult.class).asReplay();
        ConfirmBatch source = new ConfirmBatch(c.trainerId(), c.planId(), c.sourcePlanVersionId(),
                c.expectedPlanAggregateVersion(), c.weekAnchorDate(), c.timezone(), c.items(), c.commandKey());
        var inspected = workout.inspectProposalSource(source);
        Instant at = store.databaseNow();
        Proposal proposal = new Proposal(UUID.randomUUID(), 0, "PENDING", c.studentId(), c.trainerId(),
                authority.periodId(), c.planId(), inspected.planVersionId(), inspected.planVersionNumber(),
                c.expectedPlanAggregateVersion(), c.weekAnchorDate(), c.timezone(),
                c.items().stream().map(ProposalItem::fromWorkout).toList(), at,
                null, null, null, null, null);
        store.save(proposal, c.commandKey());
        recordAudit(c.trainerId(), "TRAINER", "WORKOUT_SCHEDULE_PROPOSAL_CREATED", proposal.proposalId(), at);
        CommandResult result = new CommandResult(proposal, false);
        store.saveReceipt(c.trainerId(), c.commandKey(), "PROPOSE", hash, proposal.proposalId(),
                null, json(result), at);
        return result;
    }

    @Override @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Page listMine(UUID studentId, String status, int page, int size) {
        requireStudent(studentId);
        if (page < 0 || size < 1 || size > 100 || (status != null && !List.of(
                "PENDING", "ACCEPTED", "REJECTED", "WITHDRAWN").contains(status)))
            throw fail("WORKOUT_SCHEDULE_VALIDATION_FAILED", 400);
        long count = store.countStudent(studentId, status);
        return new Page(store.listStudent(studentId, status, size, (long) page * size), page, size,
                count, (int) ((count + size - 1) / size));
    }

    // Coaching's current-authority query locks the grant/period boundary even for reads.
    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Proposal detail(UUID actorId, UUID proposalId) {
        if (actorId == null || proposalId == null) throw notFound();
        Proposal proposal = store.find(proposalId).orElseThrow(ScheduleProposalService::notFound);
        if (actorId.equals(proposal.studentId())) {
            requireStudent(actorId);
        } else if (actorId.equals(proposal.trainerId())) {
            trainerAuthority(actorId, proposal.studentId(), DataAccessLevel.VIEW, true);
        } else throw notFound();
        return proposal;
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public CommandResult withdraw(Withdraw c) {
        if (c == null || c.trainerId() == null || c.proposalId() == null || c.expectedProposalVersion() < 0)
            throw fail("WORKOUT_SCHEDULE_VALIDATION_FAILED", 400);
        key(c.commandKey());
        Proposal observed = store.find(c.proposalId()).orElseThrow(ScheduleProposalService::notFound);
        if (!c.trainerId().equals(observed.trainerId())) throw notFound();
        workout.lockStudentAndActor(observed.studentId(), c.trainerId());
        trainerAuthority(c.trainerId(), observed.studentId(), DataAccessLevel.CONTRIBUTE, true);
        String hash = hash("WITHDRAW", c.proposalId(), c.expectedProposalVersion());
        var prior = store.lockReceipt(c.trainerId(), c.commandKey());
        if (prior.isPresent()) return replay(prior.get(), "WITHDRAW", hash, CommandResult.class).asReplay();
        Proposal locked = store.lock(c.proposalId()).orElseThrow(ScheduleProposalService::notFound);
        if (!locked.trainerId().equals(c.trainerId())) throw notFound();
        checkPendingVersion(locked, c.expectedProposalVersion());
        Instant at = store.databaseNow();
        Proposal changed = transitioned(locked, "WITHDRAWN", null, null, null, at, c.trainerId());
        store.transition(changed, "PENDING", c.trainerId(), c.commandKey(), at, null);
        recordAudit(c.trainerId(), "TRAINER", "WORKOUT_SCHEDULE_PROPOSAL_WITHDRAWN", locked.proposalId(), at);
        CommandResult result = new CommandResult(changed, false);
        store.saveReceipt(c.trainerId(), c.commandKey(), "WITHDRAW", hash, locked.proposalId(),
                null, json(result), at);
        return result;
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public DecisionResult decide(Decide c) {
        if (c == null || c.studentId() == null || c.proposalId() == null || c.expectedProposalVersion() < 0
                || !List.of("ACCEPT", "REJECT").contains(c.decision())
                || (c.decisionNote() != null && c.decisionNote().length() > 1000))
            throw fail("WORKOUT_SCHEDULE_VALIDATION_FAILED", 400);
        key(c.commandKey());
        Proposal observed = store.find(c.proposalId()).orElseThrow(ScheduleProposalService::notFound);
        if (!c.studentId().equals(observed.studentId())) throw notFound();
        workout.lockStudentAndActor(c.studentId(), c.studentId());
        requireStudent(c.studentId());
        String hash = hash("DECIDE", c.proposalId(), c.expectedProposalVersion(), c.decision(), c.decisionNote());
        var prior = store.lockReceipt(c.studentId(), c.commandKey());
        if (prior.isPresent()) return replay(prior.get(), "DECIDE", hash, DecisionResult.class).asReplay();
        Proposal locked = store.lock(c.proposalId()).orElseThrow(ScheduleProposalService::notFound);
        if (!locked.studentId().equals(c.studentId())) throw notFound();
        checkPendingVersion(locked, c.expectedProposalVersion());
        WorkoutScheduleBatchUseCase.ConfirmedBatch batch = null;
        if ("ACCEPT".equals(c.decision())) {
            AuthorityDecision authority = coaching.evaluateCurrent(new AuthorityRequest(locked.trainerId(),
                    locked.studentId(), DataScope.WORKOUT_PLAN, DataAccessLevel.CONTRIBUTE));
            if (!authority.allowed() || !locked.coachingPeriodId().equals(authority.periodId()))
                throw fail("WORKOUT_SCHEDULE_PROPOSAL_INVALIDATED", 409);
            ConfirmBatch source = new ConfirmBatch(c.studentId(), locked.planId(), locked.sourcePlanVersionId(),
                    locked.observedPlanAggregateVersion(), locked.weekAnchorDate(), locked.timezone(),
                    locked.items().stream().map(ProposalItem::asWorkoutItem).toList(), c.commandKey());
            batch = workout.confirmProposal(source, locked.proposalId(), locked.trainerId());
        }
        Instant at = batch == null ? store.databaseNow() : batch.confirmedAt();
        Proposal changed = transitioned(locked, "ACCEPT".equals(c.decision()) ? "ACCEPTED" : "REJECTED",
                at, c.studentId(), c.decisionNote(), null, null);
        store.transition(changed, "PENDING", c.studentId(), c.commandKey(), at,
                batch == null ? null : batch.batchId());
        recordAudit(c.studentId(), "STUDENT", "WORKOUT_SCHEDULE_PROPOSAL_" + changed.status(),
                locked.proposalId(), at);
        DecisionResult result = new DecisionResult(changed, batch, false);
        store.saveReceipt(c.studentId(), c.commandKey(), "DECIDE", hash, locked.proposalId(),
                batch == null ? null : batch.batchId(), json(result), at);
        return result;
    }

    private AuthorityDecision trainerAuthority(UUID trainerId, UUID studentId, DataAccessLevel level,
                                               boolean conceal) {
        AuthorityDecision decision = coaching.evaluateCurrent(new AuthorityRequest(
                trainerId, studentId, DataScope.WORKOUT_PLAN, level));
        if (decision.allowed() && decision.periodId() != null) return decision;
        if (conceal || decision.reason() == com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityReason.RELATIONSHIP_NOT_ACTIVE)
            throw notFound();
        throw fail("WORKOUT_SCHEDULE_ACCESS_DENIED", 403);
    }

    private void requireStudent(UUID studentId) {
        if (studentId == null || accounts.getAccountStatus(studentId).orElse(null) != AccountStatus.ACTIVE
                || !students.hasProfile(studentId)) throw fail("WORKOUT_SCHEDULE_ACCESS_DENIED", 403);
    }

    private static void checkPendingVersion(Proposal p, long expected) {
        if (!"PENDING".equals(p.status())) throw fail("WORKOUT_SCHEDULE_PROPOSAL_STATE_CONFLICT", 409);
        if (p.proposalVersion() != expected) throw fail("WORKOUT_SCHEDULE_PROPOSAL_VERSION_CONFLICT", 409);
    }
    private static Proposal transitioned(Proposal p, String status, Instant decidedAt, UUID decidedBy,
                                         String note, Instant withdrawnAt, UUID withdrawnBy) {
        return new Proposal(p.proposalId(), p.proposalVersion() + 1, status, p.studentId(), p.trainerId(),
                p.coachingPeriodId(), p.planId(), p.sourcePlanVersionId(), p.sourcePlanVersionNumber(),
                p.observedPlanAggregateVersion(), p.weekAnchorDate(), p.timezone(), p.items(), p.proposedAt(),
                decidedAt, decidedBy, note, withdrawnAt, withdrawnBy);
    }
    private void recordAudit(UUID actor, String role, String action, UUID target, Instant at) {
        audit.recordAudit(AuditRecord.builder().actorUserId(actor).actorRole(role).action(action)
                .targetType("WORKOUT_SCHEDULE_PROPOSAL").targetId(target)
                .beforeDataJson("{}").afterDataJson("{}").occurredAt(at).build());
    }
    private static String key(String value) {
        if (value == null || value.isBlank() || value.length() > 120)
            throw fail("WORKOUT_SCHEDULE_VALIDATION_FAILED", 400);
        return value;
    }
    private String hash(Object... values) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json(Arrays.asList(values)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Cannot serialize schedule proposal", failure); }
    }
    private <T> T replay(ScheduleProposalStore.Receipt receipt, String name, String hash, Class<T> type) {
        if (!name.equals(receipt.commandName()) || !hash.equals(receipt.payloadHash()))
            throw fail("WORKOUT_SCHEDULE_IDEMPOTENCY_CONFLICT", 409);
        try { return mapper.readValue(receipt.responseJson(), type); }
        catch (Exception failure) { throw new IllegalStateException("Cannot read schedule receipt", failure); }
    }
    private static WorkoutScheduleFailure fail(String code, int status) {
        return new WorkoutScheduleFailure(code, status);
    }
    private static WorkoutScheduleFailure notFound() {
        return fail("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND", 404);
    }
}
