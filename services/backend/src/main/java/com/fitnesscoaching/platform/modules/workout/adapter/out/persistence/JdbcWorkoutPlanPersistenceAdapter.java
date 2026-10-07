package com.fitnesscoaching.platform.modules.workout.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutPlanPersistencePort;
import com.fitnesscoaching.platform.modules.workout.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcWorkoutPlanPersistenceAdapter implements WorkoutPlanPersistencePort {
    private final JdbcTemplate jdbc;
    public JdbcWorkoutPlanPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }

    @Override public UUID findPlanStudent(UUID planId) {
        return jdbc.query("SELECT student_id FROM fitness.workout_plans WHERE id=?",
                (rs, n) -> rs.getObject(1, UUID.class), planId).stream().findFirst().orElseThrow(() ->
                new WorkoutPlanFailure(WorkoutPlanError.WORKOUT_PLAN_NOT_FOUND, "Workout plan not found"));
    }

    @Override public UUID findOccurrenceStudent(UUID occurrenceId) {
        return jdbc.query("SELECT student_id FROM fitness.planned_workouts WHERE id=?",
                (rs, n) -> rs.getObject(1, UUID.class), occurrenceId).stream().findFirst().orElseThrow(() ->
                new WorkoutPlanFailure(WorkoutPlanError.PLANNED_WORKOUT_NOT_FOUND, "Planned workout not found"));
    }

    @Override public void lockStudentAndActor(UUID studentId, UUID actorId) {
        jdbc.query("SELECT user_id FROM fitness.student_profiles WHERE user_id=? FOR NO KEY UPDATE",
                rs -> { while (rs.next()) {} }, studentId);
        jdbc.query("SELECT id FROM fitness.users WHERE id=? FOR SHARE", rs -> { while (rs.next()) {} }, actorId);
    }

    @Override public Optional<Receipt> lockReceipt(UUID actorId, String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { while (rs.next()) {} }, actorId + ":" + key);
        return jdbc.query("SELECT * FROM fitness.workout_plan_command_receipts WHERE actor_id=? AND command_key=? FOR UPDATE",
                (rs, n) -> new Receipt(actorId, key, rs.getString("command_name"), rs.getString("payload_hash"),
                        rs.getObject("workout_plan_id", UUID.class), rs.getObject("workout_plan_version_id", UUID.class),
                        rs.getObject("planned_workout_id", UUID.class), rs.getString("resulting_status"),
                        (Long) rs.getObject("resulting_version"),
                        Optional.ofNullable(rs.getTimestamp("effective_at")).map(Timestamp::toInstant).orElse(null),
                        rs.getString("response_json"), rs.getTimestamp("created_at").toInstant()), actorId, key)
                .stream().findFirst();
    }

    @Override public WorkoutPlan lockPlan(UUID planId) {
        try {
            return jdbc.query("""
                    SELECT id,student_id,status::text,version,decision_owner_type::text,decision_owner_id,
                           coaching_period_id,based_on_plan_id,based_on_plan_version_id
                    FROM fitness.workout_plans WHERE id=? FOR UPDATE
                    """, (rs, n) -> new WorkoutPlan(rs.getObject("id", UUID.class),
                    rs.getObject("student_id", UUID.class), WorkoutPlanStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"), DecisionOwnerType.valueOf(rs.getString("decision_owner_type")),
                    rs.getObject("decision_owner_id", UUID.class), rs.getObject("coaching_period_id", UUID.class),
                    rs.getObject("based_on_plan_id", UUID.class), rs.getObject("based_on_plan_version_id", UUID.class)), planId)
                    .stream().findFirst().orElseThrow(() -> new WorkoutPlanFailure(
                            WorkoutPlanError.WORKOUT_PLAN_NOT_FOUND, "Workout plan not found"));
        } catch (CannotAcquireLockException concurrent) {
            throw conflict(WorkoutPlanError.WORKOUT_PLAN_VERSION_CONFLICT);
        }
    }

    @Override public OpenVersion lockOpenVersion(UUID planId) {
        try {
            return jdbc.query("""
                    SELECT id,version_number,effective_from FROM fitness.workout_plan_versions
                    WHERE workout_plan_id=? AND effective_until IS NULL FOR UPDATE
                    """, (rs, n) -> new OpenVersion(rs.getObject("id", UUID.class), rs.getInt("version_number"),
                    rs.getTimestamp("effective_from").toInstant()), planId).stream().findFirst()
                    .orElseThrow(() -> new WorkoutPlanFailure(WorkoutPlanError.WORKOUT_PLAN_VERSION_NOT_FOUND,
                            "Current workout plan version not found"));
        } catch (CannotAcquireLockException concurrent) {
            throw conflict(WorkoutPlanError.WORKOUT_PLAN_VERSION_CONFLICT);
        }
    }

    @Override public Integer findVersionNumber(UUID planId, UUID versionId) {
        return jdbc.query("""
                SELECT version_number FROM fitness.workout_plan_versions
                WHERE workout_plan_id=? AND id=?
                """, (rs, row) -> rs.getInt(1), planId, versionId).stream().findFirst().orElse(null);
    }

    @Override public List<UUID> exerciseVariationIds(UUID versionId) {
        return jdbc.query("""
                SELECT e.exercise_variation_id FROM fitness.workout_plan_session_exercises e
                JOIN fitness.workout_plan_sessions s ON s.id=e.workout_plan_session_id
                WHERE s.workout_plan_version_id=? ORDER BY e.exercise_variation_id
                """, (rs, n) -> rs.getObject(1, UUID.class), versionId);
    }

    @Override public Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    @Override public boolean hasAnotherActivePlan(UUID studentId, UUID excludingPlanId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.workout_plans
                WHERE student_id=? AND status='ACTIVE' AND id<>?)
                """, Boolean.class, studentId, excludingPlanId));
    }

    @Override public Successor createDraft(UUID studentId, UUID periodId, UUID actorId,
                                           DecisionOwnerType ownerType, String name, String description,
                                           Instant at, List<WorkoutSessionTemplate> sessions) {
        UUID planId = UUID.randomUUID(); UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_plans
                (id,student_id,coaching_period_id,name,description,source,status,created_by,
                 decision_owner_type,decision_owner_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?::fitness.plan_source,'DRAFT',?,?::fitness.workout_plan_decision_owner,?,?,?)
                """, planId, studentId, periodId, name, description, ownerType.name(), actorId,
                ownerType.name(), ownerType == DecisionOwnerType.STUDENT ? studentId : actorId, ts(at), ts(at));
        jdbc.update("""
                INSERT INTO fitness.workout_plan_versions
                (id,workout_plan_id,version_number,effective_from,change_level,change_reason,created_by,created_at)
                VALUES (?,?,1,?,'INITIAL','DRAFT_CREATED',?,?)
                """, versionId, planId, ts(at), actorId, ts(at));
        insertSessions(versionId, sessions);
        history(planId, null, WorkoutPlanStatus.DRAFT, actorId, "DRAFT_CREATED", 0, at);
        return new Successor(planId, versionId);
    }

    @Override public void updateDraft(UUID planId, UUID versionId, UUID actorId, long expected,
                                      String name, String description, Instant at,
                                      List<WorkoutSessionTemplate> sessions) {
        Timestamp lockedAt = jdbc.queryForObject(
                "SELECT locked_at FROM fitness.workout_plan_versions WHERE id=? FOR UPDATE", Timestamp.class, versionId);
        if (lockedAt != null) throw new WorkoutPlanFailure(WorkoutPlanError.WORKOUT_PLAN_IMMUTABLE,
                "Locked workout plan version is immutable");
        int changed = jdbc.update("""
                UPDATE fitness.workout_plans SET name=?,description=?,version=version+1,updated_at=?
                WHERE id=? AND status='DRAFT' AND version=?
                """, name, description, ts(at), planId, expected);
        if (changed != 1) throw conflict(WorkoutPlanError.WORKOUT_PLAN_VERSION_CONFLICT);
        jdbc.update("DELETE FROM fitness.workout_plan_sessions WHERE workout_plan_version_id=?", versionId);
        insertSessions(versionId, sessions);
    }

    @Override public void activate(UUID planId, UUID versionId, UUID actorId, long expected, Instant at) {
        int locked = jdbc.update("""
                UPDATE fitness.workout_plan_versions SET effective_from=?,locked_at=?,locked_by=?,lock_reason='ACTIVATED'
                WHERE id=? AND locked_at IS NULL
                """, ts(at), ts(at), actorId, versionId);
        int changed;
        try {
            changed = jdbc.update("""
                    UPDATE fitness.workout_plans SET status='ACTIVE',version=version+1,assigned_by=?,assigned_at=?,
                        updated_at=? WHERE id=? AND status='DRAFT' AND version=?
                    """, actorId, ts(at), ts(at), planId, expected);
        } catch (DataIntegrityViolationException ex) {
            throw conflict(WorkoutPlanError.ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS);
        }
        if (locked != 1 || changed != 1) throw conflict(WorkoutPlanError.WORKOUT_PLAN_VERSION_CONFLICT);
        history(planId, WorkoutPlanStatus.DRAFT, WorkoutPlanStatus.ACTIVE, actorId, "INITIAL_ACTIVATION", expected + 1, at);
    }

    @Override public void transition(UUID planId, UUID actorId, long expected, WorkoutPlanStatus from,
                                     WorkoutPlanStatus to, String reason, Instant at) {
        int changed;
        try {
            changed = jdbc.update("""
                    UPDATE fitness.workout_plans SET status=?::fitness.plan_status,version=version+1,updated_at=?,
                        archived_at=CASE WHEN ?='ARCHIVED' THEN ? ELSE archived_at END
                    WHERE id=? AND status=?::fitness.plan_status AND version=?
                    """, to.name(), ts(at), to.name(), ts(at), planId, from.name(), expected);
        } catch (DataIntegrityViolationException ex) {
            if (to == WorkoutPlanStatus.ACTIVE)
                throw conflict(WorkoutPlanError.ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS);
            throw ex;
        }
        if (changed != 1) throw conflict(WorkoutPlanError.WORKOUT_PLAN_VERSION_CONFLICT);
        history(planId, from, to, actorId, reason, expected + 1, at);
    }

    @Override public UUID publish(UUID planId, OpenVersion previous, UUID actorId, long expected, Instant boundary,
                                  String reason, String summary, List<WorkoutSessionTemplate> sessions) {
        if (!boundary.isAfter(previous.effectiveFrom())) throw conflict(WorkoutPlanError.WORKOUT_PLAN_EFFECTIVE_TIME_CONFLICT);
        int closed = jdbc.update("UPDATE fitness.workout_plan_versions SET effective_until=? WHERE id=? AND effective_until IS NULL",
                ts(boundary), previous.id());
        if (closed != 1) throw conflict(WorkoutPlanError.WORKOUT_PLAN_VERSION_CONFLICT);
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_plan_versions
                (id,workout_plan_id,version_number,effective_from,change_level,change_reason,change_summary,created_by,created_at)
                VALUES (?,?,?,?,'MAJOR',?,?,?,?)
                """, versionId, planId, previous.versionNumber() + 1, ts(boundary), reason, summary, actorId, ts(boundary));
        insertSessions(versionId, sessions);
        jdbc.update("""
                UPDATE fitness.workout_plan_versions SET locked_at=?,locked_by=?,lock_reason='ACTIVATED' WHERE id=?
                """, ts(boundary), actorId, versionId);
        int changed = jdbc.update("UPDATE fitness.workout_plans SET version=version+1,updated_at=? WHERE id=? AND version=?",
                ts(boundary), planId, expected);
        if (changed != 1) throw conflict(WorkoutPlanError.WORKOUT_PLAN_VERSION_CONFLICT);
        return versionId;
    }

    private void insertSessions(UUID versionId, List<WorkoutSessionTemplate> sessions) {
        for (WorkoutSessionTemplate session : sessions == null ? List.<WorkoutSessionTemplate>of() : sessions) {
            UUID sessionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.workout_plan_sessions
                    (id,workout_plan_version_id,week_number,day_number,sequence_number,name,session_focus,
                     estimated_duration_minutes,notes) VALUES (?,?,?,?,?,?,?,?,?)
                    """, sessionId, versionId, session.weekNumber(), session.dayNumber(), session.sequenceNumber(),
                    session.name(), session.focus(), session.estimatedDurationMinutes(), session.notes());
            for (ExercisePrescription exercise : session.prescriptions()) {
                jdbc.update("""
                        INSERT INTO fitness.workout_plan_session_exercises
                        (id,workout_plan_session_id,exercise_variation_id,sequence_number,target_sets,
                         target_reps_min,target_reps_max,target_load,rest_seconds,duration_seconds,instructions)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?)
                        """, UUID.randomUUID(), sessionId, exercise.exerciseVariationId(), exercise.sequenceNumber(),
                        exercise.targetSets(), exercise.targetRepsMin(), exercise.targetRepsMax(), exercise.targetLoad(),
                        exercise.restSeconds(), exercise.durationSeconds(), exercise.instructions());
            }
        }
    }

    @Override public Successor createStudentSuccessor(UUID sourcePlanId, UUID sourceVersionId, UUID studentId,
                                                       UUID periodId, String name, Instant at) {
        UUID planId = UUID.randomUUID(); UUID versionId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO fitness.workout_plans
                (id,student_id,fitness_goal_id,coaching_period_id,name,description,source,status,created_by,
                 decision_owner_type,decision_owner_id,based_on_plan_id,based_on_plan_version_id,created_at,updated_at)
                SELECT ?,p.student_id,p.fitness_goal_id,?,COALESCE(?,p.name),p.description,'STUDENT','DRAFT',?,
                       'STUDENT',?,p.id,v.id,?,?
                FROM fitness.workout_plans p JOIN fitness.workout_plan_versions v ON v.workout_plan_id=p.id
                WHERE p.id=? AND v.id=? AND p.student_id=? AND v.locked_at IS NOT NULL
                """, planId, periodId, name, studentId, studentId, ts(at), ts(at), sourcePlanId, sourceVersionId, studentId);
        if (inserted != 1) throw new WorkoutPlanFailure(WorkoutPlanError.WORKOUT_PLAN_VERSION_NOT_FOUND,
                "Source workout plan version not found");
        jdbc.update("""
                INSERT INTO fitness.workout_plan_versions
                (id,workout_plan_id,version_number,effective_from,change_level,change_reason,created_by,created_at)
                VALUES (?,?,1,?,'INITIAL','STUDENT_SUCCESSOR',?,?)
                """, versionId, planId, ts(at), studentId, ts(at));
        history(planId, null, WorkoutPlanStatus.DRAFT, studentId, "STUDENT_SUCCESSOR_CREATED", 0, at);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_sessions
                (id,workout_plan_version_id,week_number,day_number,sequence_number,name,session_focus,
                 estimated_duration_minutes,notes,supervision_requirement)
                SELECT gen_random_uuid(),?,week_number,day_number,sequence_number,name,session_focus,
                       estimated_duration_minutes,notes,supervision_requirement
                FROM fitness.workout_plan_sessions WHERE workout_plan_version_id=?
                """, versionId, sourceVersionId);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_session_exercises
                (id,workout_plan_session_id,exercise_variation_id,sequence_number,superset_group,target_sets,
                 target_reps_min,target_reps_max,target_load,load_unit_id,target_rpe,target_rir,rest_seconds,tempo,
                 duration_seconds,distance_value,distance_unit_id,instructions)
                SELECT gen_random_uuid(),ns.id,e.exercise_variation_id,e.sequence_number,e.superset_group,e.target_sets,
                       e.target_reps_min,e.target_reps_max,e.target_load,e.load_unit_id,e.target_rpe,e.target_rir,
                       e.rest_seconds,e.tempo,e.duration_seconds,e.distance_value,e.distance_unit_id,e.instructions
                FROM fitness.workout_plan_session_exercises e
                JOIN fitness.workout_plan_sessions os ON os.id=e.workout_plan_session_id
                JOIN fitness.workout_plan_sessions ns ON ns.workout_plan_version_id=?
                 AND ns.week_number=os.week_number AND ns.day_number=os.day_number AND ns.sequence_number=os.sequence_number
                WHERE os.workout_plan_version_id=?
                """, versionId, sourceVersionId);
        return new Successor(planId, versionId);
    }

    @Override public Occurrence lockOccurrence(UUID id) {
        try {
            return jdbc.query("""
                SELECT pw.id,pw.student_id,v.workout_plan_id,v.id plan_version_id,pw.version
                FROM fitness.planned_workouts pw
                JOIN fitness.workout_plan_sessions s ON s.id=pw.workout_plan_session_id
                JOIN fitness.workout_plan_versions v ON v.id=s.workout_plan_version_id
                WHERE pw.id=? FOR UPDATE OF pw
                """, (rs, n) -> new Occurrence(rs.getObject("id", UUID.class), rs.getObject("student_id", UUID.class),
                rs.getObject("workout_plan_id", UUID.class), rs.getObject("plan_version_id", UUID.class),
                rs.getLong("version")), id).stream().findFirst().orElseThrow(() -> new WorkoutPlanFailure(
                    WorkoutPlanError.PLANNED_WORKOUT_NOT_FOUND, "Planned workout not found"));
        } catch (CannotAcquireLockException concurrent) {
            throw conflict(WorkoutPlanError.PLANNED_WORKOUT_VERSION_CONFLICT);
        }
    }

    @Override public UUID appendAdjustment(Occurrence occurrence, UUID actorId, WorkoutSessionAdjustment.Type type,
                                           UUID prescriptionId, UUID replacementId, String beforeJson, String afterJson,
                                           String reason, long expected, Instant at) {
        if (prescriptionId != null && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM fitness.planned_workouts pw
                    JOIN fitness.workout_plan_session_exercises e
                      ON e.workout_plan_session_id=pw.workout_plan_session_id
                    WHERE pw.id=? AND e.id=?
                )
                """, Boolean.class, occurrence.id(), prescriptionId))) {
            throw new WorkoutPlanFailure(WorkoutPlanError.VALIDATION_FAILED,
                    "Adjustment prescription does not belong to occurrence");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_session_adjustments
                (id,planned_workout_id,adjustment_type,planned_session_exercise_id,replacement_exercise_variation_id,
                 before_value,after_value,reason,adjusted_by,created_at)
                VALUES (?,?,?, ?,?, ?::jsonb,?::jsonb,?,?,?)
                """, id, occurrence.id(), type.name(), prescriptionId, replacementId, beforeJson, afterJson,
                reason, actorId, ts(at));
        int changed = jdbc.update("UPDATE fitness.planned_workouts SET version=version+1,updated_at=? WHERE id=? AND version=?",
                ts(at), occurrence.id(), expected);
        if (changed != 1) throw conflict(WorkoutPlanError.PLANNED_WORKOUT_VERSION_CONFLICT);
        return id;
    }

    @Override public void saveReceipt(Receipt r) {
        jdbc.update("""
                INSERT INTO fitness.workout_plan_command_receipts
                (actor_id,command_key,command_name,payload_hash,workout_plan_id,workout_plan_version_id,
                 planned_workout_id,resulting_status,resulting_version,effective_at,response_json,created_at)
                VALUES (?,?,?,?,?,?,?,?::fitness.plan_status,?,?,?::jsonb,?)
                """, r.actorId(), r.commandKey(), r.commandName(), r.payloadHash(), r.planId(), r.planVersionId(),
                r.plannedWorkoutId(), r.status(), r.resultingVersion(), ts(r.effectiveAt()), r.responseJson(), ts(r.createdAt()));
    }

    private void history(UUID planId, WorkoutPlanStatus from, WorkoutPlanStatus to, UUID actorId,
                         String reason, long version, Instant at) {
        jdbc.update("""
                INSERT INTO fitness.workout_plan_status_history
                (workout_plan_id,from_status,to_status,changed_by,reason,plan_version,changed_at)
                VALUES (?,?::fitness.plan_status,?::fitness.plan_status,?,?,?,?)
                """, planId, from == null ? null : from.name(), to.name(), actorId,
                reason == null || reason.isBlank() ? "UNSPECIFIED" : reason, version, ts(at));
    }

    private static WorkoutPlanFailure conflict(WorkoutPlanError error) {
        return new WorkoutPlanFailure(error, error.name());
    }
}
