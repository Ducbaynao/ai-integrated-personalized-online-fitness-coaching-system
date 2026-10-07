package com.fitnesscoaching.platform.modules.workout.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.*;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutPlanQueryPort;
import com.fitnesscoaching.platform.modules.workout.domain.DecisionOwnerType;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcWorkoutPlanQueryAdapter implements WorkoutPlanQueryPort {
    private final JdbcTemplate jdbc;
    public JdbcWorkoutPlanQueryAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String PLAN_SELECT = """
            SELECT p.id,p.student_id,p.fitness_goal_id,p.coaching_period_id,p.name,p.description,
                   p.source::text,p.status::text,p.version,p.decision_owner_type::text,p.decision_owner_id,
                   p.based_on_plan_id,p.based_on_plan_version_id,p.created_at,p.updated_at,p.archived_at,
                   v.id current_version_id,v.version_number current_version_number,
                   v.effective_from current_version_effective_from,v.locked_at current_version_locked_at
            FROM fitness.workout_plans p
            LEFT JOIN fitness.workout_plan_versions v
              ON v.workout_plan_id=p.id AND v.effective_until IS NULL
            """;

    @Override public List<PlanSummary> findByStudent(UUID studentId) {
        return jdbc.query(PLAN_SELECT + " WHERE p.student_id=? ORDER BY p.created_at DESC,p.id DESC",
                this::plan, studentId);
    }

    @Override public Optional<PlanSummary> findActiveByStudent(UUID studentId) {
        return jdbc.query(PLAN_SELECT + " WHERE p.student_id=? AND p.status='ACTIVE'", this::plan, studentId)
                .stream().findFirst();
    }

    @Override public Optional<PlanSummary> findPlan(UUID planId) {
        return jdbc.query(PLAN_SELECT + " WHERE p.id=?", this::plan, planId).stream().findFirst();
    }

    @Override public List<VersionSummary> findVersions(UUID planId) {
        return jdbc.query("""
                SELECT id,workout_plan_id,version_number,effective_from,effective_until,change_level,
                       change_reason,change_summary,created_by,created_at,locked_at
                FROM fitness.workout_plan_versions WHERE workout_plan_id=?
                ORDER BY version_number DESC
                """, this::version, planId);
    }

    @Override public Optional<VersionDetail> findVersion(UUID planId, UUID versionId) {
        Optional<VersionSummary> version = jdbc.query("""
                SELECT id,workout_plan_id,version_number,effective_from,effective_until,change_level,
                       change_reason,change_summary,created_by,created_at,locked_at
                FROM fitness.workout_plan_versions WHERE workout_plan_id=? AND id=?
                """, this::version, planId, versionId).stream().findFirst();
        if (version.isEmpty()) return Optional.empty();
        List<SessionView> sessions = jdbc.query("""
                SELECT id,week_number,day_number,sequence_number,name,session_focus,
                       estimated_duration_minutes,notes
                FROM fitness.workout_plan_sessions WHERE workout_plan_version_id=?
                ORDER BY week_number,day_number,sequence_number,id
                """, (rs, row) -> new SessionView(rs.getObject("id", UUID.class), rs.getInt("week_number"),
                rs.getInt("day_number"), rs.getInt("sequence_number"), rs.getString("name"),
                rs.getString("session_focus"), (Integer) rs.getObject("estimated_duration_minutes"),
                rs.getString("notes"), prescriptions(rs.getObject("id", UUID.class))), versionId);
        return Optional.of(new VersionDetail(version.get(), sessions));
    }

    private List<PrescriptionView> prescriptions(UUID sessionId) {
        return jdbc.query("""
                SELECT id,exercise_variation_id,sequence_number,target_sets,target_reps_min,target_reps_max,
                       target_load,rest_seconds,duration_seconds,instructions
                FROM fitness.workout_plan_session_exercises WHERE workout_plan_session_id=?
                ORDER BY sequence_number,id
                """, (rs, row) -> new PrescriptionView(rs.getObject("id", UUID.class),
                rs.getObject("exercise_variation_id", UUID.class), rs.getInt("sequence_number"),
                (Integer) rs.getObject("target_sets"), (Integer) rs.getObject("target_reps_min"),
                (Integer) rs.getObject("target_reps_max"), rs.getBigDecimal("target_load"),
                (Integer) rs.getObject("rest_seconds"), (Integer) rs.getObject("duration_seconds"),
                rs.getString("instructions"), null), sessionId);
    }

    private PlanSummary plan(ResultSet rs, int row) throws SQLException {
        return new PlanSummary(rs.getObject("id", UUID.class), rs.getObject("student_id", UUID.class),
                rs.getObject("fitness_goal_id", UUID.class), rs.getObject("coaching_period_id", UUID.class),
                rs.getString("name"), rs.getString("description"), rs.getString("source"),
                WorkoutPlanStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                DecisionOwnerType.valueOf(rs.getString("decision_owner_type")),
                rs.getObject("decision_owner_id", UUID.class), rs.getObject("based_on_plan_id", UUID.class),
                rs.getObject("based_on_plan_version_id", UUID.class), instant(rs, "created_at"),
                instant(rs, "updated_at"), instant(rs, "archived_at"),
                rs.getObject("current_version_id", UUID.class), (Integer) rs.getObject("current_version_number"),
                instant(rs, "current_version_effective_from"), instant(rs, "current_version_locked_at"), null);
    }

    private VersionSummary version(ResultSet rs, int row) throws SQLException {
        Timestamp until = rs.getTimestamp("effective_until");
        return new VersionSummary(rs.getObject("id", UUID.class), rs.getObject("workout_plan_id", UUID.class),
                rs.getInt("version_number"), rs.getTimestamp("effective_from").toInstant(),
                until == null ? null : until.toInstant(), rs.getString("change_level"),
                rs.getString("change_reason"), rs.getString("change_summary"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                instant(rs, "locked_at"), until == null, null);
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
