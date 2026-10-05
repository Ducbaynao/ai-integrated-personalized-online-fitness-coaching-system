package com.fitnesscoaching.platform.modules.coaching.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.StudentCapabilityUnavailableException;
import com.fitnesscoaching.platform.common.exception.TrainerCapabilityUnavailableException;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.CoachingFailure;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.out.CoachingStore;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.*;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerAvailabilityQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerCoachingEligibilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class CoachingLifecycleService implements CoachingLifecycleUseCase {
    private final CoachingStore store;
    private final UserAccountStatusQuery accounts;
    private final UserRoleQuery roles;
    private final StudentCapabilityQuery students;
    private final TrainerCoachingEligibilityQuery trainers;
    private final TrainerAvailabilityQuery availability;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    public CoachingLifecycleService(CoachingStore store, UserAccountStatusQuery accounts, UserRoleQuery roles,
                                    StudentCapabilityQuery students, TrainerCoachingEligibilityQuery trainers,
                                    TrainerAvailabilityQuery availability, AuditService audit,
                                    ObjectMapper mapper, Clock clock) {
        this.store = store; this.accounts = accounts; this.roles = roles; this.students = students;
        this.trainers = trainers; this.availability = availability; this.audit = audit;
        this.mapper = mapper; this.clock = clock;
    }

    @Override @Transactional
    public Outcome initiate(UUID actor, UUID studentId, UUID trainerId, UUID key) {
        requireKey(key);
        if (studentId == null || trainerId == null || studentId.equals(trainerId)) {
            throw failure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }
        if (!actor.equals(studentId) && !actor.equals(trainerId)) {
            throw failure(HttpStatus.NOT_FOUND, "COACHING_RELATIONSHIP_NOT_FOUND");
        }
        if (actor.equals(studentId)) {
            verifyStudent(actor);
        } else {
            verifyTrainerCapability(actor);
        }
        store.lockStudent(studentId);
        String command = actor.equals(studentId) ? "REQUEST" : "INVITE";
        return projectCommand(actor, replay(actor, key, command, studentId + ":" + trainerId, () -> {
            if (actor.equals(studentId)) { verifyTargetTrainer(trainerId); }
            else { verifyTrainer(actor); verifyTargetStudent(studentId); }
            if (!availability.isAcceptingStudents(trainerId)) {
                throw failure(HttpStatus.FORBIDDEN, "TRAINER_NOT_ELIGIBLE");
            }
            if (store.pendingPair(studentId, trainerId)) {
                throw failure(HttpStatus.CONFLICT, "COACHING_REQUEST_ALREADY_PENDING");
            }
            Instant at = Instant.now(clock);
            Relationship rel = store.create(studentId, trainerId, actor, at);
            audit(actor, rel, command, null, "PENDING", at);
            return new Outcome(rel, null, null);
        }));
    }

    @Override @Transactional
    public Outcome relationshipAction(UUID actor, UUID id, String action, long expectedVersion,
                                      String reason, UUID key) {
        requireKey(key);
        Relationship rel = visible(actor, id);
        store.lockStudent(rel.studentId());
        rel = visible(actor, id);
        Relationship current = rel;
        String command = "RELATIONSHIP_" + action;
        return projectCommand(actor, replay(actor, key, command,
                id + ":" + expectedVersion + ":" + normalized(reason), () -> {
            validateReason(reason);
            checkVersion(current.version(), expectedVersion, "COACHING_VERSION_CONFLICT");
            String next = switch (action) {
                case "ACCEPT" -> { requireState(current, "PENDING"); requireCounterparty(current.requestedBy(), actor);
                    availability.lockForCoachingDecision(current.trainerId());
                    verifyStudent(current.studentId()); verifyTrainer(current.trainerId());
                    if (!availability.isAcceptingStudents(current.trainerId())) {
                        throw failure(HttpStatus.FORBIDDEN, "TRAINER_NOT_ELIGIBLE");
                    }
                    if (store.currentRelationship(current.studentId()).isPresent()) {
                        throw failure(HttpStatus.CONFLICT, "COACHING_STUDENT_ALREADY_ASSIGNED");
                    }
                    yield "ACTIVE";
                }
                case "REJECT" -> { requireState(current, "PENDING"); requireCounterparty(current.requestedBy(), actor); yield "REJECTED"; }
                case "CANCEL" -> { requireState(current, "PENDING"); requireInitiator(current.requestedBy(), actor); yield "CANCELLED"; }
                case "PAUSE" -> { requireState(current, "ACTIVE"); yield "PAUSED"; }
                case "END" -> { if (!current.status().equals("ACTIVE") && !current.status().equals("PAUSED")) {
                    throw failure(HttpStatus.CONFLICT, "COACHING_RELATIONSHIP_STATE_CONFLICT");
                } yield "ENDED"; }
                default -> throw failure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
            };
            if (next.equals("ENDED") && normalized(reason).isBlank()) {
                throw failure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
            }
            // PostgreSQL time is the authoritative lifecycle boundary; period closure rechecks
            // effectiveness at execution so expiry after selection becomes a stable conflict.
            Instant requestedAt = store.transitionTime();
            Period oldPeriod = store.effectivePeriod(current.studentId(), requestedAt).orElse(null);
            Instant at = effectiveAt(oldPeriod, requestedAt);
            if (next.equals("ACTIVE")) {
                closeIfSelfDirected(oldPeriod, at);
            } else if (current.status().equals("ACTIVE") && (next.equals("PAUSED") || next.equals("ENDED"))) {
                closeHuman(oldPeriod, current.id(), at);
            } else if (current.status().equals("PAUSED") && next.equals("ENDED")
                    && oldPeriod != null && !oldPeriod.mode().equals("SELF_DIRECTED")) {
                throw failure(HttpStatus.CONFLICT, "COACHING_PERIOD_CONFLICT");
            }
            Relationship updated = store.transition(current, next, actor, normalized(reason), at);
            if (next.equals("ACTIVE")) {
                store.openPeriod(current.studentId(), "HUMAN_COACH", current.id(), current.trainerId(), actor, at);
            } else if (current.status().equals("ACTIVE") && (next.equals("PAUSED") || next.equals("ENDED"))) {
                store.openPeriod(current.studentId(), "SELF_DIRECTED", null, null, actor, at);
            } else if (current.status().equals("PAUSED") && next.equals("ENDED") && oldPeriod == null) {
                store.openPeriod(current.studentId(), "SELF_DIRECTED", null, null, actor, at);
            }
            if (next.equals("ENDED")) {
                store.cancelPendingResume(current.id(), actor, at)
                        .ifPresent(cancelled -> auditResume(actor, current, cancelled,
                                "RESUME_INVALIDATED_BY_END", "PENDING", "CANCELLED", at));
            }
            audit(actor, updated, command, current.status(), next, at);
            return new Outcome(updated, null, null);
        }));
    }

    @Override @Transactional
    public Outcome createResume(UUID actor, UUID id, long expectedVersion, String reason, UUID key) {
        requireKey(key);
        Relationship rel = visible(actor, id);
        store.lockStudent(rel.studentId());
        rel = visible(actor, id);
        Relationship current = rel;
        return projectCommand(actor, replay(actor, key, "RESUME_REQUEST",
                id + ":" + expectedVersion + ":" + normalized(reason), () -> {
            validateReason(reason);
            checkVersion(current.version(), expectedVersion, "COACHING_VERSION_CONFLICT");
            requireState(current, "PAUSED");
            verifyTrainer(current.trainerId());
            if (store.pendingResume(id).isPresent()) {
                throw failure(HttpStatus.CONFLICT, "COACHING_RESUME_ALREADY_PENDING");
            }
            Instant at = Instant.now(clock);
            Resume request = store.createResume(id, actor, normalized(reason), at);
            auditResume(actor, current, request, "RESUME_REQUEST", "NONE", "PENDING", at);
            return new Outcome(current, request, null);
        }));
    }

    @Override @Transactional
    public Outcome resumeAction(UUID actor, UUID id, UUID resumeId, String action,
                                long expectedRelationshipVersion, long expectedRequestVersion, UUID key) {
        requireKey(key);
        Relationship rel = visible(actor, id);
        store.lockStudent(rel.studentId());
        rel = visible(actor, id);
        Relationship current = rel;
        return projectCommand(actor, replay(actor, key, "RESUME_" + action,
                id + ":" + resumeId + ":" + expectedRelationshipVersion + ":" + expectedRequestVersion, () -> {
                    checkVersion(current.version(), expectedRelationshipVersion, "COACHING_VERSION_CONFLICT");
                    requireState(current, "PAUSED");
                    Resume request = store.resume(resumeId).filter(r -> r.relationshipId().equals(id))
                            .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "COACHING_RESUME_REQUEST_NOT_FOUND"));
                    checkVersion(request.version(), expectedRequestVersion, "COACHING_RESUME_REQUEST_VERSION_CONFLICT");
                    if (!request.status().equals("PENDING")) {
                        throw failure(HttpStatus.CONFLICT, "COACHING_RESUME_REQUEST_STATE_CONFLICT");
                    }
                    if (action.equals("CANCEL")) { requireInitiator(request.requestedBy(), actor); }
                    else { requireCounterparty(request.requestedBy(), actor); }
                    String next = switch (action) {
                        case "ACCEPT" -> "ACCEPTED";
                        case "REJECT" -> "REJECTED";
                        case "CANCEL" -> "CANCELLED";
                        default -> throw failure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
                    };
                    Relationship updated = current;
                    Period old = null;
                    Instant at;
                    if (next.equals("ACCEPTED")) {
                        availability.lockForCoachingDecision(current.trainerId());
                        verifyStudent(current.studentId()); verifyTrainer(current.trainerId());
                        if (!availability.isAcceptingStudents(current.trainerId())) {
                            throw failure(HttpStatus.FORBIDDEN, "TRAINER_NOT_ELIGIBLE");
                        }
                        // The lifecycle boundary is sampled only after any trainer writer that
                        // held the capability locks has completed and eligibility was rechecked.
                        Instant requestedAt = store.transitionTime();
                        old = store.effectivePeriod(current.studentId(), requestedAt).orElse(null);
                        at = effectiveAt(old, requestedAt);
                        closeIfSelfDirected(old, at);
                        updated = store.transition(current, "ACTIVE", actor, "RESUME", at);
                    } else {
                        at = store.transitionTime();
                    }
                    Resume decided = store.decideResume(request, next, actor, at);
                    if (next.equals("ACCEPTED")) {
                        store.openPeriod(current.studentId(), "HUMAN_COACH", current.id(), current.trainerId(), actor, at);
                    }
                    audit(actor, updated, "RESUME_" + action, "PAUSED", updated.status(), at);
                    auditResume(actor, updated, decided, "RESUME_" + action, "PENDING", next, at);
                    return new Outcome(updated, decided, null);
                }));
    }

    @Override @Transactional(readOnly = true)
    public Outcome current(UUID actor) {
        verifyStudent(actor);
        return new Outcome(store.currentRelationship(actor).orElse(null), null,
                store.effectivePeriod(actor, now()).orElse(null));
    }

    @Override @Transactional(readOnly = true)
    public Outcome detail(UUID actor, UUID id) {
        Relationship rel = visible(actor, id);
        concealFormerTrainer(actor, rel);
        verifyTrainerRead(actor, rel);
        return new Outcome(rel, store.pendingResume(id).orElse(null), projectedPeriod(actor, rel));
    }

    @Override @Transactional(readOnly = true)
    public List<Relationship> pending(UUID actor, String direction, int page, int size) {
        verifyAccount(actor);
        if (!direction.equals("INCOMING") && !direction.equals("OUTGOING")) {
            throw failure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }
        boolean studentCapability = roles.hasActiveRole(actor, "STUDENT") && students.hasProfile(actor);
        boolean trainerCapability = roles.hasActiveRole(actor, "TRAINER") && trainers.canCoach(actor);
        if (!studentCapability && !trainerCapability) { throw failure(HttpStatus.FORBIDDEN, "ACCESS_DENIED"); }
        return store.pending(actor, direction.equals("INCOMING"), studentCapability,
                trainerCapability, size, page * size);
    }

    @Override @Transactional(readOnly = true)
    public List<History> history(UUID actor, UUID id, int page, int size) {
        Relationship rel = visible(actor, id);
        concealFormerTrainer(actor, rel);
        verifyTrainerRead(actor, rel);
        return store.history(id, size, page * size);
    }

    @Override @Transactional(readOnly = true)
    public List<Resume> resumeHistory(UUID actor, UUID id, int page, int size) {
        Relationship rel = visible(actor, id);
        concealFormerTrainer(actor, rel);
        verifyTrainerRead(actor, rel);
        return store.resumeHistory(id, size, page * size);
    }

    @Override @Transactional(readOnly = true)
    public List<Period> periods(UUID actor, int page, int size) {
        verifyStudent(actor);
        return store.periods(actor, size, page * size);
    }

    private Relationship visible(UUID actor, UUID id) {
        Relationship rel = store.relationship(id)
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "COACHING_RELATIONSHIP_NOT_FOUND"));
        if (!rel.participant(actor)) { throw failure(HttpStatus.NOT_FOUND, "COACHING_RELATIONSHIP_NOT_FOUND"); }
        if (rel.studentId().equals(actor)) { verifyStudent(actor); }
        else { verifyTrainerCapability(actor); }
        return rel;
    }

    private static void concealFormerTrainer(UUID actor, Relationship rel) {
        if (rel.trainerId().equals(actor) && rel.status().equals("ENDED")) {
            throw failure(HttpStatus.NOT_FOUND, "COACHING_RELATIONSHIP_NOT_FOUND");
        }
    }

    private void verifyTrainerRead(UUID actor, Relationship rel) {
        if (rel.trainerId().equals(actor) && rel.status().equals("ACTIVE")) { verifyTrainer(actor); }
    }

    private void verifyAccount(UUID actor) {
        if (accounts.getAccountStatus(actor).orElse(null) != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException("Account unavailable");
        }
    }

    private void verifyStudent(UUID id) {
        verifyAccount(id);
        if (!roles.hasActiveRole(id, "STUDENT") || !students.hasProfile(id)) {
            throw new StudentCapabilityUnavailableException("Student capability unavailable");
        }
    }

    private void verifyTrainerCapability(UUID id) {
        verifyAccount(id);
        if (!roles.hasActiveRole(id, "TRAINER")) {
            throw new TrainerCapabilityUnavailableException("Trainer capability unavailable");
        }
    }

    private void verifyTrainer(UUID id) {
        verifyTrainerCapability(id);
        if (!trainers.canCoach(id)) { throw failure(HttpStatus.FORBIDDEN, "TRAINER_NOT_ELIGIBLE"); }
    }

    private void verifyTargetStudent(UUID id) {
        if (accounts.getAccountStatus(id).orElse(null) != AccountStatus.ACTIVE
                || !roles.hasActiveRole(id, "STUDENT") || !students.hasProfile(id)) {
            throw failure(HttpStatus.NOT_FOUND, "COACHING_COUNTERPARTY_NOT_FOUND");
        }
    }

    private void verifyTargetTrainer(UUID id) {
        if (accounts.getAccountStatus(id).orElse(null) != AccountStatus.ACTIVE
                || !roles.hasActiveRole(id, "TRAINER")) {
            throw failure(HttpStatus.NOT_FOUND, "COACHING_COUNTERPARTY_NOT_FOUND");
        }
        if (!trainers.canCoach(id)) { throw failure(HttpStatus.FORBIDDEN, "TRAINER_NOT_ELIGIBLE"); }
    }

    private static void requireState(Relationship rel, String state) {
        if (!rel.status().equals(state)) {
            throw failure(HttpStatus.CONFLICT, "COACHING_RELATIONSHIP_STATE_CONFLICT");
        }
    }
    private static void requireCounterparty(UUID initiator, UUID actor) {
        if (initiator.equals(actor)) { throw failure(HttpStatus.FORBIDDEN, "COACHING_COUNTERPARTY_REQUIRED"); }
    }
    private static void requireInitiator(UUID initiator, UUID actor) {
        if (!initiator.equals(actor)) { throw failure(HttpStatus.FORBIDDEN, "COACHING_INITIATOR_REQUIRED"); }
    }
    private static void checkVersion(long actual, long expected, String code) {
        if (expected < 0 || actual != expected) { throw failure(HttpStatus.CONFLICT, code); }
    }
    private static void requireKey(UUID key) {
        if (key == null) { throw failure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED"); }
    }
    private static String normalized(String reason) { return reason == null ? "" : reason.trim(); }
    private static void validateReason(String reason) {
        if (reason != null && reason.length() > 500) { throw failure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED"); }
    }
    private static CoachingFailure failure(HttpStatus status, String code) { return new CoachingFailure(status, code); }

    private void closeIfSelfDirected(Period old, Instant at) {
        if (old == null) { return; } // No fabricated pre-feature history.
        if (!old.mode().equals("SELF_DIRECTED")) { throw failure(HttpStatus.CONFLICT, "COACHING_PERIOD_CONFLICT"); }
        store.closePeriod(old, at);
    }
    private void closeHuman(Period old, UUID id, Instant at) {
        if (old == null) { return; } // Legacy ACTIVE relationship with no period never had effective Trainer authority.
        if (!old.mode().equals("HUMAN_COACH") || !id.equals(old.relationshipId())) {
            throw failure(HttpStatus.CONFLICT, "COACHING_PERIOD_CONFLICT");
        }
        store.closePeriod(old, at);
    }
    private Instant now() { return Instant.now(clock).truncatedTo(ChronoUnit.MICROS); }

    private Instant effectiveAt(Period old, Instant at) {
        if (old == null) { return at; }
        // PostgreSQL timestamptz has microsecond precision. Use the same boundary for close and open.
        return at.isAfter(old.startedAt()) ? at : old.startedAt().plusNanos(1_000);
    }

    private Outcome replay(UUID actor, UUID key, String command, String payload, Supplier<Outcome> work) {
        String hash = hash(command + ":" + payload);
        Receipt prior = store.receipt(actor, key).orElse(null);
        if (prior != null) {
            if (!prior.commandName().equals(command) || !prior.payloadHash().equals(hash)) {
                throw failure(HttpStatus.CONFLICT, "COACHING_IDEMPOTENCY_CONFLICT");
            }
            Outcome saved = mapper.readValue(prior.responseJson(), Outcome.class);
            // A receipt is an idempotent domain result, never a reusable authority snapshot.
            return new Outcome(saved.relationship(), saved.resume(), null);
        }
        Outcome outcome = work.get();
        store.saveReceipt(actor, key, command, hash,
                mapper.writeValueAsString(new Outcome(outcome.relationship(), outcome.resume(), null)));
        return outcome;
    }

    private Outcome projectCommand(UUID actor, Outcome saved) {
        // Re-read current participant/capability state even when the command came from a receipt.
        Relationship current = visible(actor, saved.relationship().id());
        return new Outcome(saved.relationship(), saved.resume(), projectedPeriod(actor, current));
    }

    private Period projectedPeriod(UUID actor, Relationship relationship) {
        if (relationship.studentId().equals(actor)) {
            return store.effectivePeriod(actor, now()).orElse(null);
        }
        if (!relationship.status().equals("ACTIVE")) { return null; }
        verifyTrainer(actor);
        return store.effectivePeriod(relationship.studentId(), now())
                .filter(period -> period.mode().equals("HUMAN_COACH")
                        && relationship.id().equals(period.relationshipId())
                        && actor.equals(period.trainerId()))
                .orElse(null);
    }

    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private void audit(UUID actor, Relationship rel, String action, String before, String after, Instant at) {
        String role = rel.studentId().equals(actor) ? "STUDENT" : "TRAINER";
        audit.recordAudit(AuditRecord.builder().actorUserId(actor).actorRole(role)
                .action("COACHING_" + action).targetType("COACHING_RELATIONSHIP")
                .targetId(rel.id()).beforeDataJson("{\"status\":\"" + (before == null ? "NONE" : before) + "\"}")
                .afterDataJson("{\"status\":\"" + after + "\"}")
                .metadataJson("{}").occurredAt(at).build());
    }

    private void auditResume(UUID actor, Relationship rel, Resume request, String action,
                             String before, String after, Instant at) {
        String role = rel.studentId().equals(actor) ? "STUDENT" : "TRAINER";
        audit.recordAudit(AuditRecord.builder().actorUserId(actor).actorRole(role)
                .action("COACHING_" + action).targetType("COACHING_RESUME_REQUEST")
                .targetId(request.id()).beforeDataJson("{\"status\":\"" + before + "\"}")
                .afterDataJson("{\"status\":\"" + after + "\"}")
                .metadataJson("{\"relationshipId\":\"" + rel.id() + "\"}")
                .occurredAt(at).build());
    }
}
