package com.fitnesscoaching.platform.modules.workout.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionSnapshotUseCase.*;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionPersistencePort;
import com.fitnesscoaching.platform.modules.workout.domain.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class JdbcWorkoutExecutionPersistenceAdapter implements WorkoutExecutionPersistencePort {
    private final JdbcTemplate jdbc;
    public JdbcWorkoutExecutionPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    @Override public void lockStudent(UUID studentId) {
        jdbc.query("SELECT user_id FROM fitness.student_profiles WHERE user_id=? FOR NO KEY UPDATE",
                rs -> { while (rs.next()) {} }, studentId);
    }

    @Override public Optional<Receipt> lockReceipt(UUID actorId, String key) {
        return jdbc.query("""
                SELECT actor_id,command_key,command_name,payload_hash,planned_workout_id,workout_execution_id,
                       resulting_execution_status::text,resulting_planned_status::text,resulting_version,
                       effective_at,response_json::text,created_at
                FROM fitness.workout_execution_command_receipts
                WHERE actor_id=? AND command_key=? FOR UPDATE
                """, (rs,n) -> new Receipt(rs.getObject("actor_id",UUID.class),rs.getString("command_key"),
                rs.getString("command_name"),rs.getString("payload_hash"),
                rs.getObject("planned_workout_id",UUID.class),rs.getObject("workout_execution_id",UUID.class),
                rs.getString("resulting_execution_status"),rs.getString("resulting_planned_status"),
                (Long)rs.getObject("resulting_version"),instant(rs.getTimestamp("effective_at")),
                rs.getString("response_json"),instant(rs.getTimestamp("created_at"))),actorId,key).stream().findFirst();
    }

    @Override public boolean hasInProgress(UUID studentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.workout_session_logs
                WHERE student_id=? AND status='IN_PROGRESS')
                """, Boolean.class, studentId));
    }

    @Override public WorkoutExecution create(FrozenExecutionSnapshot snapshot, UUID actorId) {
        UUID executionId=UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO fitness.workout_session_logs
                    (id,student_id,planned_workout_id,workout_plan_version_id,workout_plan_session_id,
                     coaching_period_id,performed_start_at,status,logged_by,version,frozen_occurrence_version,
                     snapshot_frozen_at,snapshot_mode,notes,created_at,updated_at,source_workout_plan_id,
                     frozen_planned_start_at,frozen_planned_end_at,frozen_original_planned_start_at,
                     frozen_supervision_requirement,snapshot_contract_version)
                    VALUES (?,?,?,?,?,?,?,'IN_PROGRESS',?,0,?,?,'FROZEN',?,?,?,?,?,?,?,?::fitness.supervision_requirement,1)
                    """,executionId,snapshot.studentId(),snapshot.occurrenceId(),snapshot.planVersionId(),
                    snapshot.planSessionId(),snapshot.coachingPeriodId(),ts(snapshot.frozenAt()),actorId,
                    snapshot.sealedOccurrenceVersion(),ts(snapshot.frozenAt()),snapshot.sessionNote(),
                    ts(snapshot.frozenAt()),ts(snapshot.frozenAt()),snapshot.planId(),ts(snapshot.plannedStartAt()),
                    ts(snapshot.plannedEndAt()),ts(snapshot.originalPlannedStartAt()),snapshot.supervisionRequirement());
        } catch (DataIntegrityViolationException conflict) {
            String detail = Objects.toString(conflict.getMostSpecificCause().getMessage(), "");
            if (detail.contains("uq_workout_execution_one_in_progress_student"))
                throw failure(WorkoutExecutionError.ACTIVE_WORKOUT_EXECUTION_EXISTS);
            throw failure(WorkoutExecutionError.PLANNED_WORKOUT_ALREADY_STARTED);
        }
        for(FrozenPrescription p:snapshot.prescriptions()) {
            UUID exerciseId=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.exercise_logs
                    (id,workout_session_log_id,exercise_variation_id,actual_exercise_variation_id,
                     planned_session_exercise_id,sequence_number,frozen_sequence_number,baseline_set_count,
                     target_reps_min,target_reps_max,target_load,target_load_unit_id,target_rpe,target_rir,
                     target_rest_seconds,target_tempo,target_duration_seconds,target_distance_value,
                     target_distance_unit_id,frozen_instructions,frozen_prescription_note)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,exerciseId,executionId,p.prescribedVariationId(),p.prescribedVariationId(),p.prescriptionId(),
                    p.sequenceNumber(),p.sequenceNumber(),p.targetSets(),p.targetRepsMin(),p.targetRepsMax(),
                    p.targetLoad(),p.loadUnitId(),p.targetRpe(),p.targetRir(),p.restSeconds(),p.tempo(),
                    p.durationSeconds(),p.distanceValue(),p.distanceUnitId(),p.instructions(),p.note());
        }
        int order=1;
        for(UUID adjustment:snapshot.appliedAdjustmentIds()) jdbc.update("""
                INSERT INTO fitness.workout_execution_applied_adjustments
                (workout_execution_id,adjustment_id,resolution_order) VALUES (?,?,?)
                """,executionId,adjustment,order++);
        history(executionId,null,WorkoutExecutionStatus.IN_PROGRESS,actorId,"START",0,snapshot.frozenAt());
        return load(executionId,false).orElseThrow();
    }

    @Override public WorkoutExecution lockExecution(UUID id) {
        jdbc.query("SELECT id FROM fitness.workout_session_logs WHERE id=? FOR UPDATE",
                rs->{while(rs.next()){}},id);
        return load(id,true).orElseThrow(()->failure(WorkoutExecutionError.WORKOUT_EXECUTION_NOT_FOUND));
    }
    @Override public Optional<WorkoutExecution> find(UUID id) { return load(id,false); }
    @Override public Optional<SetIdentity> findSet(UUID clientSetId) {
        return jdbc.query("""
                SELECT el.workout_session_log_id,sl.exercise_log_id,sl.id,sl.client_set_id,sl.baseline_set_number,
                       sl.set_number,sl.set_type::text,sl.completion_status::text,sl.repetitions,sl.load_value,
                       sl.load_unit_id,sl.duration_seconds,sl.distance_value,sl.distance_unit_id,sl.rpe,sl.rir,
                       sl.tempo,sl.rest_after_seconds,sl.notes
                FROM fitness.set_logs sl JOIN fitness.exercise_logs el ON el.id=sl.exercise_log_id
                WHERE sl.client_set_id=?
                """,(rs,n)->new SetIdentity(rs.getObject("workout_session_log_id",UUID.class),
                rs.getObject("exercise_log_id",UUID.class),mapSet(rs)),clientSetId).stream().findFirst();
    }

    @Override public void insertSet(UUID executionId,UUID exerciseId,SetExecution set,long expected,Instant at) {
        verifyExercise(executionId,exerciseId,set.baselineSetNumber());
        try {
            jdbc.update("""
                    INSERT INTO fitness.set_logs
                    (id,exercise_log_id,client_set_id,baseline_set_number,set_number,set_type,completion_status,
                     repetitions,load_value,load_unit_id,duration_seconds,distance_value,distance_unit_id,rpe,rir,
                     tempo,rest_after_seconds,notes,completed_at,created_at,updated_at)
                    VALUES (?,?,?,?,?,?::fitness.set_type,?::fitness.set_completion_status,?,?,?,?,?,?,?,?,?,?,?, ?,?,?)
                    """,set.id()==null?UUID.randomUUID():set.id(),exerciseId,set.clientSetId(),set.baselineSetNumber(),
                    set.setNumber(),set.setType(),set.completionStatus(),set.repetitions(),set.loadValue(),set.loadUnitId(),
                    set.durationSeconds(),set.distanceValue(),set.distanceUnitId(),set.rpe(),set.rir(),set.tempo(),
                    set.restAfterSeconds(),set.note(),set.completed()?ts(at):null,ts(at),ts(at));
        } catch (DataIntegrityViolationException ex) { throw failure(WorkoutExecutionError.WORKOUT_SET_IDENTITY_CONFLICT); }
        bump(executionId,expected,at);
    }

    @Override public void updateSet(UUID executionId,UUID exerciseId,SetExecution set,long expected,Instant at) {
        verifyExercise(executionId,exerciseId,set.baselineSetNumber());
        int changed=jdbc.update("""
                UPDATE fitness.set_logs SET baseline_set_number=?,set_number=?,set_type=?::fitness.set_type,
                    completion_status=?::fitness.set_completion_status,repetitions=?,load_value=?,load_unit_id=?,
                    duration_seconds=?,distance_value=?,distance_unit_id=?,rpe=?,rir=?,tempo=?,rest_after_seconds=?,
                    notes=?,completed_at=?,updated_at=? WHERE client_set_id=? AND exercise_log_id=?
                """,set.baselineSetNumber(),set.setNumber(),set.setType(),set.completionStatus(),set.repetitions(),
                set.loadValue(),set.loadUnitId(),set.durationSeconds(),set.distanceValue(),set.distanceUnitId(),set.rpe(),
                set.rir(),set.tempo(),set.restAfterSeconds(),set.note(),set.completed()?ts(at):null,ts(at),
                set.clientSetId(),exerciseId);
        if(changed!=1) throw failure(WorkoutExecutionError.WORKOUT_SET_IDENTITY_CONFLICT);
        bump(executionId,expected,at);
    }

    @Override public void substitute(UUID executionId,UUID exerciseId,UUID variationId,String reason,long expected,Instant at) {
        int changed=jdbc.update("""
                UPDATE fitness.exercise_logs SET actual_exercise_variation_id=?,substitution_reason=?
                WHERE id=? AND workout_session_log_id=?
                """,variationId,reason,exerciseId,executionId);
        if(changed!=1) throw failure(WorkoutExecutionError.WORKOUT_EXECUTION_NOT_FOUND);
        bump(executionId,expected,at);
    }

    @Override public void terminal(UUID id,WorkoutExecutionStatus from,WorkoutExecutionStatus to,UUID actor,long expected,
                                   java.math.BigDecimal overallRpe,String note,Instant at) {
        int changed=jdbc.update("""
                UPDATE fitness.workout_session_logs SET status=?::fitness.actual_workout_status,performed_end_at=?,
                    overall_rpe=?,notes=?,version=version+1,updated_at=?
                WHERE id=? AND status=?::fitness.actual_workout_status AND version=?
                """,to.name(),ts(at),overallRpe,note,ts(at),id,from.name(),expected);
        if(changed!=1) throw failure(WorkoutExecutionError.WORKOUT_EXECUTION_VERSION_CONFLICT);
        history(id,from,to,actor,to.name(),expected+1,at);
    }

    @Override public void saveReceipt(Receipt r) {
        jdbc.update("""
                INSERT INTO fitness.workout_execution_command_receipts
                (actor_id,command_key,command_name,payload_hash,planned_workout_id,workout_execution_id,
                 resulting_execution_status,resulting_planned_status,resulting_version,effective_at,response_json,created_at)
                VALUES (?,?,?,?,?,?,?::fitness.actual_workout_status,?::fitness.planned_workout_status,?,?,?::jsonb,?)
                """,r.actorId(),r.commandKey(),r.commandName(),r.payloadHash(),r.plannedWorkoutId(),r.executionId(),
                r.executionStatus(),r.plannedStatus(),r.resultingVersion(),ts(r.effectiveAt()),r.responseJson(),ts(r.createdAt()));
    }
    @Override public Instant databaseNow(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}

    private Optional<WorkoutExecution> load(UUID id,boolean lockChildren) {
        Optional<WorkoutExecution> root=jdbc.query("""
                SELECT id,student_id,planned_workout_id,workout_plan_version_id,workout_plan_session_id,
                       coaching_period_id,frozen_occurrence_version,snapshot_mode,performed_start_at,performed_end_at,status::text,
                       overall_rpe,notes,version FROM fitness.workout_session_logs
                WHERE id=? AND snapshot_mode='FROZEN'
                """,(rs,n)->new WorkoutExecution(rs.getObject("id",UUID.class),rs.getObject("student_id",UUID.class),
                rs.getObject("planned_workout_id",UUID.class),rs.getObject("workout_plan_version_id",UUID.class),
                rs.getObject("workout_plan_session_id",UUID.class),rs.getObject("coaching_period_id",UUID.class),
                (Long)rs.getObject("frozen_occurrence_version"),rs.getString("snapshot_mode"),
                instant(rs.getTimestamp("performed_start_at")),
                instant(rs.getTimestamp("performed_end_at")),WorkoutExecutionStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("overall_rpe"),rs.getString("notes"),rs.getLong("version"),List.of()),id)
                .stream().findFirst();
        if(root.isEmpty())return root;
        String suffix=lockChildren?" FOR UPDATE":"";
        List<ExerciseExecution> exercises=jdbc.query("""
                SELECT id,planned_session_exercise_id,exercise_variation_id,actual_exercise_variation_id,
                       substitution_reason,frozen_sequence_number,baseline_set_count,target_reps_min,target_reps_max,
                       target_load,target_load_unit_id,target_rpe,target_rir,target_rest_seconds,target_tempo,
                       target_duration_seconds,target_distance_value,target_distance_unit_id,frozen_instructions,
                       frozen_prescription_note FROM fitness.exercise_logs WHERE workout_session_log_id=?
                ORDER BY frozen_sequence_number,id"""+suffix,(rs,n)->new ExerciseExecution(rs.getObject("id",UUID.class),
                rs.getObject("planned_session_exercise_id",UUID.class),rs.getObject("exercise_variation_id",UUID.class),
                rs.getObject("actual_exercise_variation_id",UUID.class),rs.getString("substitution_reason"),
                rs.getInt("frozen_sequence_number"),rs.getInt("baseline_set_count"),
                (Integer)rs.getObject("target_reps_min"),(Integer)rs.getObject("target_reps_max"),
                rs.getBigDecimal("target_load"),shortValue(rs,"target_load_unit_id"),rs.getBigDecimal("target_rpe"),
                rs.getBigDecimal("target_rir"),(Integer)rs.getObject("target_rest_seconds"),rs.getString("target_tempo"),
                (Integer)rs.getObject("target_duration_seconds"),rs.getBigDecimal("target_distance_value"),
                shortValue(rs,"target_distance_unit_id"),rs.getString("frozen_instructions"),
                rs.getString("frozen_prescription_note"),sets(rs.getObject("id",UUID.class),lockChildren)),id);
        WorkoutExecution r=root.get();
        return Optional.of(new WorkoutExecution(r.id(),r.studentId(),r.plannedWorkoutId(),r.planVersionId(),
                r.planSessionId(),r.coachingPeriodId(),r.frozenOccurrenceVersion(),r.snapshotMode(),r.performedStartedAt(),
                r.performedEndedAt(),r.status(),r.overallRpe(),r.sessionNote(),r.version(),exercises));
    }

    private List<SetExecution> sets(UUID exerciseId,boolean lock) {
        return jdbc.query("""
                SELECT id,client_set_id,baseline_set_number,set_number,set_type::text,completion_status::text,
                       repetitions,load_value,load_unit_id,duration_seconds,distance_value,distance_unit_id,
                       rpe,rir,tempo,rest_after_seconds,notes FROM fitness.set_logs WHERE exercise_log_id=?
                ORDER BY set_number,id"""+(lock?" FOR UPDATE":""),(rs,n)->mapSet(rs),exerciseId);
    }
    private static SetExecution mapSet(ResultSet rs)throws SQLException{return new SetExecution(
            rs.getObject("id",UUID.class),rs.getObject("client_set_id",UUID.class),(Integer)rs.getObject("baseline_set_number"),
            rs.getInt("set_number"),rs.getString("set_type"),rs.getString("completion_status"),
            (Integer)rs.getObject("repetitions"),rs.getBigDecimal("load_value"),shortValue(rs,"load_unit_id"),
            (Integer)rs.getObject("duration_seconds"),rs.getBigDecimal("distance_value"),shortValue(rs,"distance_unit_id"),
            rs.getBigDecimal("rpe"),rs.getBigDecimal("rir"),rs.getString("tempo"),(Integer)rs.getObject("rest_after_seconds"),
            rs.getString("notes"));}
    private void verifyExercise(UUID executionId,UUID exerciseId,Integer baseline){
        Integer count=jdbc.query("SELECT baseline_set_count FROM fitness.exercise_logs WHERE id=? AND workout_session_log_id=?",
                (rs,n)->rs.getInt(1),exerciseId,executionId).stream().findFirst().orElseThrow(()->failure(WorkoutExecutionError.WORKOUT_EXECUTION_NOT_FOUND));
        if(baseline!=null&&baseline>count)throw failure(WorkoutExecutionError.VALIDATION_FAILED);
    }
    private void bump(UUID id,long expected,Instant at){int changed=jdbc.update("""
            UPDATE fitness.workout_session_logs SET version=version+1,updated_at=?
            WHERE id=? AND version=? AND status='IN_PROGRESS'""",ts(at),id,expected);
        if(changed!=1)throw failure(WorkoutExecutionError.WORKOUT_EXECUTION_VERSION_CONFLICT);}
    private void history(UUID id,WorkoutExecutionStatus from,WorkoutExecutionStatus to,UUID actor,String reason,long version,Instant at){
        jdbc.update("""
                INSERT INTO fitness.workout_execution_status_history
                (workout_execution_id,from_status,to_status,changed_by,reason,execution_version,changed_at)
                VALUES (?,?::fitness.actual_workout_status,?::fitness.actual_workout_status,?,?,?,?)""",
                id,from==null?null:from.name(),to.name(),actor,reason,version,ts(at));}
    private static Short shortValue(ResultSet rs,String column)throws SQLException{
        Number value=(Number)rs.getObject(column);
        return value==null?null:value.shortValue();
    }
    private static WorkoutExecutionFailure failure(WorkoutExecutionError error){return new WorkoutExecutionFailure(error,error.name());}
}
