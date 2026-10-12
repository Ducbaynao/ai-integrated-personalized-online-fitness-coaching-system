package com.fitnesscoaching.platform.modules.schedule.application.service;

import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleChangeProposalUseCase;
import com.fitnesscoaching.platform.modules.schedule.application.port.out.ScheduleChangeProposalStore;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleProposalSourceQuery;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ScheduleChangeProposalService implements ScheduleChangeProposalUseCase {
    private final ScheduleChangeProposalStore store;
    private final WorkoutScheduleChangeUseCase workout;
    private final WorkoutScheduleProposalSourceQuery sources;
    private final CoachingAuthorityQuery coaching;
    private final StudentCapabilityQuery students;
    private final UserAccountStatusQuery accounts;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public ScheduleChangeProposalService(ScheduleChangeProposalStore store, WorkoutScheduleChangeUseCase workout,
                                         WorkoutScheduleProposalSourceQuery sources, CoachingAuthorityQuery coaching,
                                         StudentCapabilityQuery students, UserAccountStatusQuery accounts,
                                         AuditService audit, ObjectMapper mapper) {
        this.store=store; this.workout=workout; this.sources=sources; this.coaching=coaching;
        this.students=students; this.accounts=accounts; this.audit=audit; this.mapper=mapper;
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public CommandResult create(Create c) {
        if (c == null || c.trainerId() == null || c.studentId() == null || c.occurrenceId() == null
                || c.expectedOccurrenceVersion() < 0 || c.reason() != null && c.reason().length() > 1000)
            throw fail("WORKOUT_SCHEDULE_CHANGE_VALIDATION_FAILED", 400);
        key(c.commandKey());
        workout.lockStudentAndActor(c.studentId(), c.trainerId());
        AuthorityDecision authority = trainerAuthority(c.trainerId(), c.studentId(), DataAccessLevel.CONTRIBUTE, false);
        String hash = hash("PROPOSE", c.studentId(), c.occurrenceId(), c.expectedOccurrenceVersion(),
                c.changeType(), c.destination(), c.reason());
        var prior = store.lockReceipt(c.trainerId(), c.commandKey());
        if (prior.isPresent()) return replay(prior.get(), "PROPOSE", hash, CommandResult.class).asReplay();
        var source = workout.inspectProposalCreation(c.studentId(), c.occurrenceId(),
                c.expectedOccurrenceVersion(), c.changeType(), c.destination());
        UUID planId = null;
        if (source.planVersionId() != null) {
            var plan = sources.findByVersionIds(List.of(source.planVersionId())).get(source.planVersionId());
            if (plan != null) planId = plan.planId();
        }
        Instant at = store.databaseNow();
        Proposal proposal = new Proposal(UUID.randomUUID(), 0, "PENDING", c.changeType(), c.studentId(),
                c.trainerId(), authority.periodId(), c.occurrenceId(), c.expectedOccurrenceVersion(),
                source.plannedStartAt(), source.plannedEndAt(), source.timezone(), local(source.localStart()),
                local(source.localEnd()), source.startUtcOffset(), source.endUtcOffset(),
                source.originalPlannedStartAt(), planId, source.planVersionId(), source.planSessionId(),
                c.destination(), c.reason(), at, null, null, null, null);
        store.save(proposal, c.commandKey());
        audit(c.trainerId(), "TRAINER", "WORKOUT_SCHEDULE_CHANGE_PROPOSAL_CREATED", proposal.proposalId(), at);
        CommandResult result = new CommandResult(proposal, false);
        store.saveReceipt(c.trainerId(), c.commandKey(), "PROPOSE", hash, proposal.proposalId(), json(result), at);
        return result;
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Page listMine(UUID studentId, String status, int page, int size) {
        requireStudent(studentId);
        validateList(status, page, size);
        long count = store.countStudent(studentId, status);
        return new Page(store.listStudent(studentId, status, size, (long) page * size),
                page, size, count, (int) ((count + size - 1) / size));
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Page listForTrainer(UUID trainerId, UUID studentId, String status, int page, int size) {
        if (trainerId == null || studentId == null) throw notFound();
        trainerAuthority(trainerId, studentId, DataAccessLevel.VIEW, true);
        validateList(status, page, size);
        long count = store.countTrainer(trainerId, studentId, status);
        return new Page(store.listTrainer(trainerId, studentId, status, size, (long) page * size),
                page, size, count, (int) ((count + size - 1) / size));
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Proposal detail(UUID actorId, UUID proposalId) {
        if (actorId == null || proposalId == null) throw notFound();
        Proposal p = store.find(proposalId).orElseThrow(ScheduleChangeProposalService::notFound);
        if (actorId.equals(p.studentId())) requireStudent(actorId);
        else if (actorId.equals(p.trainerId())) trainerAuthority(actorId, p.studentId(), DataAccessLevel.VIEW, true);
        else throw notFound();
        return p;
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public CommandResult withdraw(Withdraw c) {
        if (c == null || c.trainerId() == null || c.proposalId() == null || c.expectedProposalVersion() < 0)
            throw fail("WORKOUT_SCHEDULE_CHANGE_VALIDATION_FAILED", 400);
        key(c.commandKey());
        Proposal observed = store.find(c.proposalId()).orElseThrow(ScheduleChangeProposalService::notFound);
        if (!c.trainerId().equals(observed.trainerId())) throw notFound();
        workout.lockStudentAndActor(observed.studentId(), c.trainerId());
        trainerAuthority(c.trainerId(), observed.studentId(), DataAccessLevel.CONTRIBUTE, true);
        String hash = hash("WITHDRAW", c.proposalId(), c.expectedProposalVersion());
        var prior = store.lockReceipt(c.trainerId(), c.commandKey());
        if (prior.isPresent()) return replay(prior.get(), "WITHDRAW", hash, CommandResult.class).asReplay();
        Proposal locked = store.lock(c.proposalId()).orElseThrow(ScheduleChangeProposalService::notFound);
        if (!locked.trainerId().equals(c.trainerId())) throw notFound();
        checkPending(locked, c.expectedProposalVersion());
        Instant at = store.databaseNow();
        Proposal changed = transition(locked, "WITHDRAWN", null, null, at, c.trainerId());
        store.transition(changed, "PENDING", c.trainerId(), c.commandKey(), null, at);
        audit(c.trainerId(), "TRAINER", "WORKOUT_SCHEDULE_CHANGE_PROPOSAL_WITHDRAWN", locked.proposalId(), at);
        CommandResult result = new CommandResult(changed, false);
        store.saveReceipt(c.trainerId(), c.commandKey(), "WITHDRAW", hash, locked.proposalId(), json(result), at);
        return result;
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public DecisionResult decide(Decide c) {
        if (c == null || c.studentId() == null || c.proposalId() == null || c.expectedProposalVersion() < 0
                || !List.of("ACCEPT", "REJECT").contains(c.decision())
                || c.decisionReason() != null && c.decisionReason().length() > 1000)
            throw fail("WORKOUT_SCHEDULE_CHANGE_VALIDATION_FAILED", 400);
        key(c.commandKey());
        Proposal observed = store.find(c.proposalId()).orElseThrow(ScheduleChangeProposalService::notFound);
        if (!c.studentId().equals(observed.studentId())) throw notFound();
        workout.lockStudentAndActor(c.studentId(), c.studentId());
        requireStudent(c.studentId());
        String hash = hash("DECIDE", c.proposalId(), c.expectedProposalVersion(), c.decision(), c.decisionReason());
        var prior = store.lockReceipt(c.studentId(), c.commandKey());
        if (prior.isPresent()) return replay(prior.get(), "DECIDE", hash, DecisionResult.class).asReplay();
        Proposal locked = store.lock(c.proposalId()).orElseThrow(ScheduleChangeProposalService::notFound);
        if (!locked.studentId().equals(c.studentId())) throw notFound();
        checkPending(locked, c.expectedProposalVersion());
        WorkoutScheduleChangeUseCase.ChangeResult applied = null;
        if ("ACCEPT".equals(c.decision())) {
            AuthorityDecision authority = coaching.evaluateCurrent(new AuthorityRequest(locked.trainerId(),
                    locked.studentId(), DataScope.WORKOUT_PLAN, DataAccessLevel.CONTRIBUTE));
            if (!authority.allowed() || !locked.coachingPeriodId().equals(authority.periodId()))
                throw fail("WORKOUT_SCHEDULE_CHANGE_PROPOSAL_STALE", 409);
            String internalKey = "proposal-" + hash(locked.proposalId(), c.commandKey());
            try {
                if ("RESCHEDULE".equals(locked.changeType())) applied = workout.reschedule(
                        new WorkoutScheduleChangeUseCase.Reschedule(locked.occurrenceId(), c.studentId(),
                                locked.observedOccurrenceVersion(), locked.destination(), c.decisionReason(), internalKey));
                else applied = workout.cancel(new WorkoutScheduleChangeUseCase.Cancel(locked.occurrenceId(),
                        c.studentId(), locked.observedOccurrenceVersion(), c.decisionReason(), internalKey));
            } catch (WorkoutScheduleFailure failure) {
                if (List.of("WORKOUT_SCHEDULE_OCCURRENCE_VERSION_CONFLICT",
                        "WORKOUT_SCHEDULE_OCCURRENCE_SEALED", "WORKOUT_SCHEDULE_OCCURRENCE_NOT_CHANGEABLE")
                        .contains(failure.code())) throw fail("WORKOUT_SCHEDULE_CHANGE_PROPOSAL_STALE", 409);
                throw failure;
            }
        }
        Instant at = applied == null ? store.databaseNow() : applied.effectiveAt();
        Proposal changed = transition(locked, "ACCEPT".equals(c.decision()) ? "ACCEPTED" : "REJECTED",
                at, c.studentId(), null, null);
        store.transition(changed, "PENDING", c.studentId(), c.commandKey(),
                applied == null ? null : locked.occurrenceId(), at);
        audit(c.studentId(), "STUDENT", "WORKOUT_SCHEDULE_CHANGE_PROPOSAL_" + changed.status(),
                locked.proposalId(), at);
        DecisionResult result = new DecisionResult(changed, applied, false);
        store.saveReceipt(c.studentId(), c.commandKey(), "DECIDE", hash, locked.proposalId(), json(result), at);
        return result;
    }

    private AuthorityDecision trainerAuthority(UUID trainer, UUID student, DataAccessLevel level, boolean conceal) {
        AuthorityDecision d = coaching.evaluateCurrent(new AuthorityRequest(trainer, student,
                DataScope.WORKOUT_PLAN, level));
        if (d.allowed() && d.periodId() != null) return d;
        if (conceal || d.reason() == com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityReason.RELATIONSHIP_NOT_ACTIVE)
            throw notFound();
        throw fail("WORKOUT_SCHEDULE_CHANGE_ACCESS_DENIED", 403);
    }
    private void requireStudent(UUID student) {
        if (student == null || accounts.getAccountStatus(student).orElse(null) != AccountStatus.ACTIVE
                || !students.hasProfile(student)) throw fail("WORKOUT_SCHEDULE_CHANGE_ACCESS_DENIED", 403);
    }
    private static void validateList(String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || status != null && !List.of(
                "PENDING", "ACCEPTED", "REJECTED", "WITHDRAWN").contains(status))
            throw fail("WORKOUT_SCHEDULE_CHANGE_VALIDATION_FAILED", 400);
    }
    private static void checkPending(Proposal p, long expected) {
        if (!"PENDING".equals(p.status())) throw fail("WORKOUT_SCHEDULE_CHANGE_PROPOSAL_STATE_CONFLICT", 409);
        if (p.proposalVersion() != expected)
            throw fail("WORKOUT_SCHEDULE_CHANGE_PROPOSAL_VERSION_CONFLICT", 409);
    }
    private static Proposal transition(Proposal p, String status, Instant decidedAt, UUID decidedBy,
                                       Instant withdrawnAt, UUID withdrawnBy) {
        return new Proposal(p.proposalId(), p.proposalVersion()+1, status, p.changeType(), p.studentId(),
                p.trainerId(), p.coachingPeriodId(), p.occurrenceId(), p.observedOccurrenceVersion(),
                p.oldPlannedStartAt(), p.oldPlannedEndAt(), p.oldTimezone(), p.oldLocalStart(), p.oldLocalEnd(),
                p.oldStartUtcOffset(), p.oldEndUtcOffset(), p.originalPlannedStartAt(), p.sourcePlanId(),
                p.sourcePlanVersionId(), p.sourcePlanSessionId(), p.destination(), p.reason(), p.proposedAt(),
                decidedAt, decidedBy, withdrawnAt, withdrawnBy);
    }
    private static LocalDateTime local(String value) {
        if (value == null) return null;
        try { return LocalDateTime.parse(value); }
        catch (DateTimeParseException legacy) { return null; }
    }
    private void audit(UUID actor, String role, String action, UUID target, Instant at) {
        audit.recordAudit(AuditRecord.builder().actorUserId(actor).actorRole(role).action(action)
                .targetType("WORKOUT_SCHEDULE_CHANGE_PROPOSAL").targetId(target)
                .beforeDataJson("{}").afterDataJson("{}").occurredAt(at).build());
    }
    private static String key(String value) {
        if (value == null || value.isBlank() || value.length() > 120)
            throw fail("WORKOUT_SCHEDULE_CHANGE_VALIDATION_FAILED", 400);
        return value;
    }
    private String hash(Object... values) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json(Arrays.asList(values)).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Cannot serialize change proposal", failure); }
    }
    private <T> T replay(ScheduleChangeProposalStore.Receipt receipt, String name, String hash, Class<T> type) {
        if (!name.equals(receipt.commandName()) || !hash.equals(receipt.payloadHash()))
            throw fail("WORKOUT_SCHEDULE_IDEMPOTENCY_CONFLICT", 409);
        try { return mapper.readValue(receipt.responseJson(), type); }
        catch (Exception failure) { throw new IllegalStateException("Cannot read change proposal receipt", failure); }
    }
    private static WorkoutScheduleFailure notFound() {
        return fail("WORKOUT_SCHEDULE_CHANGE_PROPOSAL_NOT_FOUND", 404);
    }
    private static WorkoutScheduleFailure fail(String code, int status) { return new WorkoutScheduleFailure(code, status); }
}
