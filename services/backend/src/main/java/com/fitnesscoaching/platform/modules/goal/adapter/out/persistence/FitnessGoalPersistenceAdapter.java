package com.fitnesscoaching.platform.modules.goal.adapter.out.persistence;

import com.fitnesscoaching.platform.common.exception.GoalVersionConflictException;
import com.fitnesscoaching.platform.common.exception.InvalidLifecycleTransitionException;
import com.fitnesscoaching.platform.modules.goal.application.port.out.FitnessGoalPersistencePort;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoalVersion;
import com.fitnesscoaching.platform.modules.goal.domain.GoalObjective;
import com.fitnesscoaching.platform.modules.goal.domain.GoalStatus;
import com.fitnesscoaching.platform.modules.goal.domain.GoalTarget;
import com.fitnesscoaching.platform.modules.goal.domain.ObjectivePriority;
import com.fitnesscoaching.platform.modules.goal.domain.VersionLockReason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class FitnessGoalPersistenceAdapter implements FitnessGoalPersistencePort {

    private final JdbcTemplate jdbcTemplate;

    public FitnessGoalPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<GoalObjective> OBJECTIVE_ROW_MAPPER = (rs, rowNum) -> {
        UUID id = (UUID) rs.getObject("id");
        UUID goalVersionId = (UUID) rs.getObject("goal_version_id");
        short goalTypeId = rs.getShort("goal_type_id");
        String goalTypeCode = rs.getString("goal_type_code");
        String goalTypeName = rs.getString("goal_type_name");
        String priorityStr = rs.getString("priority");
        ObjectivePriority priority = priorityStr != null ? ObjectivePriority.valueOf(priorityStr) : ObjectivePriority.SECONDARY;
        int sortOrder = rs.getInt("sort_order");
        String notes = rs.getString("notes");

        return new GoalObjective(
                id,
                goalVersionId,
                goalTypeId,
                goalTypeCode,
                goalTypeName,
                priority,
                sortOrder,
                notes
        );
    };

    private static final RowMapper<GoalTarget> TARGET_ROW_MAPPER = (rs, rowNum) -> {
        UUID id = (UUID) rs.getObject("id");
        UUID goalVersionId = (UUID) rs.getObject("goal_version_id");
        int metricDefId = rs.getInt("metric_definition_id");
        String metricCode = rs.getString("metric_code");
        String metricDisplayName = rs.getString("metric_display_name");
        UUID exerciseVariationId = (UUID) rs.getObject("exercise_variation_id");
        var startValue = rs.getBigDecimal("start_value");
        var targetValue = rs.getBigDecimal("target_value");
        var targetMinValue = rs.getBigDecimal("target_min_value");
        var targetMaxValue = rs.getBigDecimal("target_max_value");
        short unitId = rs.getShort("unit_id");
        String unitCode = rs.getString("unit_code");
        String unitSymbol = rs.getString("unit_symbol");
        int reps = rs.getInt("target_repetitions");
        Integer targetRepetitions = rs.wasNull() ? null : reps;
        Date targetDt = rs.getDate("target_date");
        LocalDate targetDate = targetDt != null ? targetDt.toLocalDate() : null;
        String notes = rs.getString("notes");
        Timestamp createdTs = rs.getTimestamp("created_at");
        Instant createdAt = createdTs != null ? createdTs.toInstant() : null;

        return new GoalTarget(
                id,
                goalVersionId,
                metricDefId,
                metricCode,
                metricDisplayName,
                exerciseVariationId,
                startValue,
                targetValue,
                targetMinValue,
                targetMaxValue,
                unitId,
                unitCode,
                unitSymbol,
                targetRepetitions,
                targetDate,
                notes,
                createdAt
        );
    };

    private static final RowMapper<FitnessGoalVersion> VERSION_ROW_MAPPER = (rs, rowNum) -> {
        UUID vId = (UUID) rs.getObject("id");
        UUID fgId = (UUID) rs.getObject("fitness_goal_id");
        int versionNumber = rs.getInt("version_number");
        String title = rs.getString("title");

        Date stDate = rs.getDate("start_date");
        LocalDate startDate = stDate != null ? stDate.toLocalDate() : null;

        Date tgDate = rs.getDate("target_date");
        LocalDate targetDate = tgDate != null ? tgDate.toLocalDate() : null;

        int dur = rs.getInt("duration_days");
        Integer durationDays = rs.wasNull() ? null : dur;

        Timestamp effFromTs = rs.getTimestamp("effective_from");
        Instant effectiveFrom = effFromTs != null ? effFromTs.toInstant() : null;

        Timestamp effUntilTs = rs.getTimestamp("effective_until");
        Instant effectiveUntil = effUntilTs != null ? effUntilTs.toInstant() : null;

        Date resDate = rs.getDate("resume_date");
        LocalDate resumeDate = resDate != null ? resDate.toLocalDate() : null;

        String changeReason = rs.getString("change_reason");
        String changeSummary = rs.getString("change_summary");
        UUID createdBy = (UUID) rs.getObject("created_by");
        UUID sourceProposalId = (UUID) rs.getObject("source_proposal_id");

        Timestamp crTs = rs.getTimestamp("created_at");
        Instant createdAt = crTs != null ? crTs.toInstant() : null;

        Timestamp lkTs = rs.getTimestamp("locked_at");
        Instant lockedAt = lkTs != null ? lkTs.toInstant() : null;

        UUID lockedBy = (UUID) rs.getObject("locked_by");
        String lockReasonStr = rs.getString("lock_reason");
        VersionLockReason lockReason = lockReasonStr != null ? VersionLockReason.valueOf(lockReasonStr) : null;

        return new FitnessGoalVersion(
                vId,
                fgId,
                versionNumber,
                title,
                startDate,
                targetDate,
                durationDays,
                effectiveFrom,
                effectiveUntil,
                resumeDate,
                changeReason,
                changeSummary,
                createdBy,
                sourceProposalId,
                createdAt,
                lockedAt,
                lockedBy,
                lockReason,
                List.of(),
                List.of()
        );
    };

    @Override
    @Transactional
    public FitnessGoal saveGoalAggregate(
            FitnessGoal goal,
            FitnessGoalVersion version,
            List<GoalObjective> objectives,
            List<GoalTarget> targets,
            boolean activateImmediately
    ) {
        UUID goalId = goal.id() != null ? goal.id() : UUID.randomUUID();
        UUID versionId = version.id() != null ? version.id() : UUID.randomUUID();

        // 1. Insert fitness_goals as DRAFT initially
        String insertGoalSql = """
                INSERT INTO fitness.fitness_goals (
                    id, student_id, title, status, created_by,
                    activated_at, paused_at, completed_at, ended_at, status_reason,
                    created_at, updated_at
                ) VALUES (
                    ?, ?, ?, 'DRAFT'::fitness.lifecycle_status, ?,
                    NULL, NULL, NULL, NULL, ?,
                    now(), now()
                )
                """;
        jdbcTemplate.update(
                insertGoalSql,
                goalId,
                goal.studentId(),
                goal.title(),
                goal.createdBy(),
                goal.statusReason()
        );

        // 2. Insert fitness_goal_versions as unlocked
        String insertVersionSql = """
                INSERT INTO fitness.fitness_goal_versions (
                    id, fitness_goal_id, version_number, title, start_date, target_date,
                    duration_days, effective_from, effective_until, resume_date,
                    change_reason, change_summary, created_by, source_proposal_id,
                    created_at, locked_at, locked_by, lock_reason
                ) VALUES (
                    ?, ?, ?, ?, ?, ?,
                    ?, now(), NULL, NULL,
                    ?, ?, ?, ?,
                    now(), NULL, NULL, NULL
                )
                """;
        jdbcTemplate.update(
                insertVersionSql,
                versionId,
                goalId,
                version.versionNumber() > 0 ? version.versionNumber() : 1,
                version.title() != null ? version.title() : goal.title(),
                Date.valueOf(version.startDate()),
                version.targetDate() != null ? Date.valueOf(version.targetDate()) : null,
                version.durationDays(),
                version.changeReason() != null ? version.changeReason() : "INITIAL_CREATION",
                version.changeSummary(),
                goal.createdBy(),
                version.sourceProposalId()
        );

        // 3. Insert objectives (allowed because version is unlocked)
        if (objectives != null && !objectives.isEmpty()) {
            String insertObjectiveSql = """
                    INSERT INTO fitness.goal_objectives (
                        id, goal_version_id, goal_type_id, priority, sort_order, notes
                    ) VALUES (
                        ?, ?, ?, ?::fitness.objective_priority, ?, ?
                    )
                    """;
            for (GoalObjective obj : objectives) {
                UUID objId = obj.id() != null ? obj.id() : UUID.randomUUID();
                jdbcTemplate.update(
                        insertObjectiveSql,
                        objId,
                        versionId,
                        obj.goalTypeId(),
                        obj.priority().name(),
                        obj.sortOrder(),
                        obj.notes()
                );
            }
        }

        // 4. Insert targets (allowed because version is unlocked)
        if (targets != null && !targets.isEmpty()) {
            String insertTargetSql = """
                    INSERT INTO fitness.goal_targets (
                        id, goal_version_id, metric_definition_id, exercise_variation_id,
                        start_value, target_value, target_min_value, target_max_value,
                        unit_id, target_repetitions, target_date, notes, created_at
                    ) VALUES (
                        ?, ?, ?, ?,
                        ?, ?, ?, ?,
                        ?, ?, ?, ?, now()
                    )
                    """;
            for (GoalTarget tgt : targets) {
                UUID tgtId = tgt.id() != null ? tgt.id() : UUID.randomUUID();
                jdbcTemplate.update(
                        insertTargetSql,
                        tgtId,
                        versionId,
                        tgt.metricDefinitionId(),
                        tgt.exerciseVariationId(),
                        tgt.startValue(),
                        tgt.targetValue(),
                        tgt.targetMinValue(),
                        tgt.targetMaxValue(),
                        tgt.unitId(),
                        tgt.targetRepetitions(),
                        tgt.targetDate() != null ? Date.valueOf(tgt.targetDate()) : null,
                        tgt.notes()
                );
            }
        }

        // 5. If activateImmediately, lock version and update goal status to ACTIVE
        if (activateImmediately) {
            String lockVersionSql = """
                    UPDATE fitness.fitness_goal_versions
                    SET locked_at = now(),
                        locked_by = ?,
                        lock_reason = 'ACTIVATED'::fitness.version_lock_reason
                    WHERE id = ?
                    """;
            jdbcTemplate.update(lockVersionSql, goal.createdBy(), versionId);

            String activateGoalSql = """
                    UPDATE fitness.fitness_goals
                    SET status = 'ACTIVE'::fitness.lifecycle_status,
                        activated_at = now(),
                        updated_at = now()
                    WHERE id = ?
                    """;
            jdbcTemplate.update(activateGoalSql, goalId);

            String insertHistorySql = """
                    INSERT INTO fitness.fitness_goal_status_history (
                        id, fitness_goal_id, from_status, to_status, changed_by, reason, changed_at
                    ) VALUES (
                        gen_random_uuid(), ?, 'DRAFT'::fitness.lifecycle_status, 'ACTIVE'::fitness.lifecycle_status, ?, ?, now()
                    )
                    """;
            jdbcTemplate.update(insertHistorySql, goalId, goal.createdBy(), "Initial goal creation and activation");
        } else {
            String insertHistorySql = """
                    INSERT INTO fitness.fitness_goal_status_history (
                        id, fitness_goal_id, from_status, to_status, changed_by, reason, changed_at
                    ) VALUES (
                        gen_random_uuid(), ?, NULL, 'DRAFT'::fitness.lifecycle_status, ?, ?, now()
                    )
                    """;
            jdbcTemplate.update(insertHistorySql, goalId, goal.createdBy(), "Initial goal draft created");
        }

        return findById(goalId).orElseThrow(() -> new IllegalStateException("Failed to load saved fitness goal: " + goalId));
    }

    @Override
    @Transactional
    public FitnessGoal activateGoal(UUID goalId, UUID studentId, String reason) {
        String activateGoalSql = """
                UPDATE fitness.fitness_goals
                SET status = 'ACTIVE'::fitness.lifecycle_status,
                    activated_at = now(),
                    updated_at = now()
                WHERE id = ? AND status = 'DRAFT'::fitness.lifecycle_status
                """;
        int goalUpdated = jdbcTemplate.update(activateGoalSql, goalId);
        if (goalUpdated == 0) {
            throw new InvalidLifecycleTransitionException(
                    "Cannot activate fitness goal: goal is not in DRAFT status");
        }

        String lockVersionSql = """
                UPDATE fitness.fitness_goal_versions
                SET locked_at = now(),
                    locked_by = ?,
                    lock_reason = 'ACTIVATED'::fitness.version_lock_reason
                WHERE fitness_goal_id = ? AND effective_until IS NULL
                """;
        int versionUpdated = jdbcTemplate.update(lockVersionSql, studentId, goalId);
        if (versionUpdated == 0) {
            throw new IllegalStateException("Failed to lock current version for goal: " + goalId);
        }

        String historyReason = (reason != null && !reason.isBlank()) ? reason : "Goal activated";
        String insertHistorySql = """
                INSERT INTO fitness.fitness_goal_status_history (
                    id, fitness_goal_id, from_status, to_status, changed_by, reason, changed_at
                ) VALUES (
                    gen_random_uuid(), ?, 'DRAFT'::fitness.lifecycle_status, 'ACTIVE'::fitness.lifecycle_status, ?, ?, now()
                )
                """;
        jdbcTemplate.update(insertHistorySql, goalId, studentId, historyReason);

        return findById(goalId).orElseThrow(() -> new IllegalStateException("Failed to load activated fitness goal: " + goalId));
    }

    @Override
    public Optional<FitnessGoal> findById(UUID goalId) {
        String sql = """
                SELECT id, student_id, title, status, created_by,
                       activated_at, paused_at, completed_at, ended_at, status_reason,
                       created_at, updated_at
                FROM fitness.fitness_goals
                WHERE id = ? AND deleted_at IS NULL
                """;
        List<FitnessGoal> goals = jdbcTemplate.query(sql, (rs, rowNum) -> {
            UUID id = (UUID) rs.getObject("id");
            UUID studentId = (UUID) rs.getObject("student_id");
            String title = rs.getString("title");
            String statusStr = rs.getString("status");
            GoalStatus status = statusStr != null ? GoalStatus.valueOf(statusStr) : GoalStatus.DRAFT;
            UUID createdBy = (UUID) rs.getObject("created_by");

            Timestamp actTs = rs.getTimestamp("activated_at");
            Instant activatedAt = actTs != null ? actTs.toInstant() : null;

            Timestamp pauseTs = rs.getTimestamp("paused_at");
            Instant pausedAt = pauseTs != null ? pauseTs.toInstant() : null;

            Timestamp compTs = rs.getTimestamp("completed_at");
            Instant completedAt = compTs != null ? compTs.toInstant() : null;

            Timestamp endTs = rs.getTimestamp("ended_at");
            Instant endedAt = endTs != null ? endTs.toInstant() : null;

            String statusReason = rs.getString("status_reason");

            Timestamp crTs = rs.getTimestamp("created_at");
            Instant createdAt = crTs != null ? crTs.toInstant() : null;

            Timestamp upTs = rs.getTimestamp("updated_at");
            Instant updatedAt = upTs != null ? upTs.toInstant() : null;

            return new FitnessGoal(
                    id,
                    studentId,
                    title,
                    status,
                    createdBy,
                    activatedAt,
                    pausedAt,
                    completedAt,
                    endedAt,
                    statusReason,
                    createdAt,
                    updatedAt,
                    null
            );
        }, goalId);

        if (goals.isEmpty()) {
            return Optional.empty();
        }

        FitnessGoal goal = goals.get(0);
        FitnessGoalVersion currentVersion = loadCurrentVersion(goalId).orElse(null);

        return Optional.of(new FitnessGoal(
                goal.id(),
                goal.studentId(),
                goal.title(),
                goal.status(),
                goal.createdBy(),
                goal.activatedAt(),
                goal.pausedAt(),
                goal.completedAt(),
                goal.endedAt(),
                goal.statusReason(),
                goal.createdAt(),
                goal.updatedAt(),
                currentVersion
        ));
    }

    @Override
    public Optional<FitnessGoal> findCurrentActiveByStudentId(UUID studentId) {
        String sql = """
                SELECT id
                FROM fitness.fitness_goals
                WHERE student_id = ?
                  AND status = 'ACTIVE'::fitness.lifecycle_status
                  AND deleted_at IS NULL
                ORDER BY activated_at DESC, created_at DESC
                LIMIT 1
                """;
        List<UUID> ids = jdbcTemplate.query(sql, (rs, rowNum) -> (UUID) rs.getObject("id"), studentId);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return findById(ids.get(0));
    }

    @Override
    public boolean hasActiveGoal(UUID studentId) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1 FROM fitness.fitness_goals
                    WHERE student_id = ?
                      AND status = 'ACTIVE'::fitness.lifecycle_status
                      AND deleted_at IS NULL
                )
                """;
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, studentId);
        return Boolean.TRUE.equals(exists);
    }

    private Optional<FitnessGoalVersion> loadCurrentVersion(UUID goalId) {
        String versionSql = """
                SELECT id, fitness_goal_id, version_number, title, start_date, target_date,
                       duration_days, effective_from, effective_until, resume_date,
                       change_reason, change_summary, created_by, source_proposal_id,
                       created_at, locked_at, locked_by, lock_reason
                FROM fitness.fitness_goal_versions
                WHERE fitness_goal_id = ? AND effective_until IS NULL
                ORDER BY version_number DESC
                LIMIT 1
                """;
        List<FitnessGoalVersion> versions = jdbcTemplate.query(versionSql, VERSION_ROW_MAPPER, goalId);

        if (versions.isEmpty()) {
            return Optional.empty();
        }

        FitnessGoalVersion v = versions.get(0);
        List<GoalObjective> objectives = loadObjectives(v.id());
        List<GoalTarget> targets = loadTargets(v.id());

        return Optional.of(new FitnessGoalVersion(
                v.id(),
                v.fitnessGoalId(),
                v.versionNumber(),
                v.title(),
                v.startDate(),
                v.targetDate(),
                v.durationDays(),
                v.effectiveFrom(),
                v.effectiveUntil(),
                v.resumeDate(),
                v.changeReason(),
                v.changeSummary(),
                v.createdBy(),
                v.sourceProposalId(),
                v.createdAt(),
                v.lockedAt(),
                v.lockedBy(),
                v.lockReason(),
                objectives,
                targets
        ));
    }

    @Override
    public List<FitnessGoalVersion> findVersionsByGoalId(UUID goalId, int limit, long offset) {
        String sql = """
                SELECT id, fitness_goal_id, version_number, title, start_date, target_date,
                       duration_days, effective_from, effective_until, resume_date,
                       change_reason, change_summary, created_by, source_proposal_id,
                       created_at, locked_at, locked_by, lock_reason
                FROM fitness.fitness_goal_versions
                WHERE fitness_goal_id = ?
                ORDER BY version_number DESC
                LIMIT ? OFFSET ?
                """;
        List<FitnessGoalVersion> versions = jdbcTemplate.query(sql, VERSION_ROW_MAPPER, goalId, limit, offset);
        if (versions.isEmpty()) {
            return List.of();
        }

        List<UUID> versionIds = versions.stream().map(FitnessGoalVersion::id).toList();

        // Batch load objectives to prevent N+1 queries
        String objSql = """
                SELECT o.id, o.goal_version_id, o.goal_type_id,
                       gt.code as goal_type_code, gt.name as goal_type_name,
                       o.priority, o.sort_order, o.notes
                FROM fitness.goal_objectives o
                JOIN fitness.goal_types gt ON gt.id = o.goal_type_id
                WHERE o.goal_version_id = ANY (?)
                ORDER BY o.sort_order ASC, o.id ASC
                """;
        Map<UUID, List<GoalObjective>> objectivesByVersion = new HashMap<>();
        jdbcTemplate.query(
                con -> {
                    var ps = con.prepareStatement(objSql);
                    ps.setArray(1, con.createArrayOf("uuid", versionIds.toArray()));
                    return ps;
                },
                rs -> {
                    UUID vId = (UUID) rs.getObject("goal_version_id");
                    GoalObjective obj = OBJECTIVE_ROW_MAPPER.mapRow(rs, 0);
                    objectivesByVersion.computeIfAbsent(vId, k -> new ArrayList<>()).add(obj);
                }
        );

        // Batch load targets to prevent N+1 queries
        String tgtSql = """
                SELECT t.id, t.goal_version_id, t.metric_definition_id,
                       md.code as metric_code, md.display_name as metric_display_name,
                       t.exercise_variation_id, t.start_value, t.target_value,
                       t.target_min_value, t.target_max_value,
                       t.unit_id, u.code as unit_code, u.symbol as unit_symbol,
                       t.target_repetitions, t.target_date, t.notes, t.created_at
                FROM fitness.goal_targets t
                JOIN fitness.metric_definitions md ON md.id = t.metric_definition_id
                JOIN fitness.measurement_units u ON u.id = t.unit_id
                WHERE t.goal_version_id = ANY (?)
                ORDER BY t.created_at ASC, t.id ASC
                """;
        Map<UUID, List<GoalTarget>> targetsByVersion = new HashMap<>();
        jdbcTemplate.query(
                con -> {
                    var ps = con.prepareStatement(tgtSql);
                    ps.setArray(1, con.createArrayOf("uuid", versionIds.toArray()));
                    return ps;
                },
                rs -> {
                    UUID vId = (UUID) rs.getObject("goal_version_id");
                    GoalTarget tgt = TARGET_ROW_MAPPER.mapRow(rs, 0);
                    targetsByVersion.computeIfAbsent(vId, k -> new ArrayList<>()).add(tgt);
                }
        );

        return versions.stream().map(v -> new FitnessGoalVersion(
                v.id(),
                v.fitnessGoalId(),
                v.versionNumber(),
                v.title(),
                v.startDate(),
                v.targetDate(),
                v.durationDays(),
                v.effectiveFrom(),
                v.effectiveUntil(),
                v.resumeDate(),
                v.changeReason(),
                v.changeSummary(),
                v.createdBy(),
                v.sourceProposalId(),
                v.createdAt(),
                v.lockedAt(),
                v.lockedBy(),
                v.lockReason(),
                objectivesByVersion.getOrDefault(v.id(), List.of()),
                targetsByVersion.getOrDefault(v.id(), List.of())
        )).toList();
    }

    @Override
    public long countVersionsByGoalId(UUID goalId) {
        String sql = "SELECT count(*) FROM fitness.fitness_goal_versions WHERE fitness_goal_id = ?";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, goalId);
        return count != null ? count : 0L;
    }

    @Override
    public Optional<FitnessGoalVersion> findVersionById(UUID goalId, UUID versionId) {
        String sql = """
                SELECT id, fitness_goal_id, version_number, title, start_date, target_date,
                       duration_days, effective_from, effective_until, resume_date,
                       change_reason, change_summary, created_by, source_proposal_id,
                       created_at, locked_at, locked_by, lock_reason
                FROM fitness.fitness_goal_versions
                WHERE fitness_goal_id = ? AND id = ?
                """;
        List<FitnessGoalVersion> versions = jdbcTemplate.query(sql, VERSION_ROW_MAPPER, goalId, versionId);
        if (versions.isEmpty()) {
            return Optional.empty();
        }

        FitnessGoalVersion v = versions.get(0);
        List<GoalObjective> objectives = loadObjectives(v.id());
        List<GoalTarget> targets = loadTargets(v.id());

        return Optional.of(new FitnessGoalVersion(
                v.id(),
                v.fitnessGoalId(),
                v.versionNumber(),
                v.title(),
                v.startDate(),
                v.targetDate(),
                v.durationDays(),
                v.effectiveFrom(),
                v.effectiveUntil(),
                v.resumeDate(),
                v.changeReason(),
                v.changeSummary(),
                v.createdBy(),
                v.sourceProposalId(),
                v.createdAt(),
                v.lockedAt(),
                v.lockedBy(),
                v.lockReason(),
                objectives,
                targets
        ));
    }

    @Override
    @Transactional
    public FitnessGoalVersion createNewGoalVersion(UUID goalId, UUID studentId, FitnessGoalVersion newVersion, UUID currentVersionId) {
        // 1. CAS close current version (effective_until = now())
        String closeVersionSql = """
                UPDATE fitness.fitness_goal_versions
                SET effective_until = now()
                WHERE id = ? AND fitness_goal_id = ? AND effective_until IS NULL
                """;
        int closed = jdbcTemplate.update(closeVersionSql, currentVersionId, goalId);
        if (closed == 0) {
            throw new GoalVersionConflictException("Current goal version has changed; unable to create version");
        }

        // 2. Insert new version (with lock fields NULL initially to allow inserting child objectives and targets)
        UUID newVersionId = newVersion.id() != null ? newVersion.id() : UUID.randomUUID();
        String insertVersionSql = """
                INSERT INTO fitness.fitness_goal_versions (
                    id, fitness_goal_id, version_number, title, start_date, target_date,
                    duration_days, effective_from, effective_until, resume_date,
                    change_reason, change_summary, created_by, source_proposal_id,
                    created_at, locked_at, locked_by, lock_reason
                ) VALUES (
                    ?, ?, ?, ?, ?, ?,
                    ?, now(), NULL, NULL,
                    ?, ?, ?, NULL,
                    now(), NULL, NULL, NULL
                )
                """;
        jdbcTemplate.update(
                insertVersionSql,
                newVersionId,
                goalId,
                newVersion.versionNumber(),
                newVersion.title(),
                Date.valueOf(newVersion.startDate()),
                newVersion.targetDate() != null ? Date.valueOf(newVersion.targetDate()) : null,
                newVersion.durationDays(),
                newVersion.changeReason(),
                newVersion.changeSummary(),
                studentId
        );

        // 3. Insert objectives (allowed because newVersion is currently unlocked)
        if (newVersion.objectives() != null && !newVersion.objectives().isEmpty()) {
            String insertObjectiveSql = """
                    INSERT INTO fitness.goal_objectives (
                        id, goal_version_id, goal_type_id, priority, sort_order, notes
                    ) VALUES (
                        ?, ?, ?, ?::fitness.objective_priority, ?, ?
                    )
                    """;
            for (GoalObjective obj : newVersion.objectives()) {
                UUID objId = obj.id() != null ? obj.id() : UUID.randomUUID();
                jdbcTemplate.update(
                        insertObjectiveSql,
                        objId,
                        newVersionId,
                        obj.goalTypeId(),
                        obj.priority().name(),
                        obj.sortOrder(),
                        obj.notes()
                );
            }
        }

        // 4. Insert targets (allowed because newVersion is currently unlocked)
        if (newVersion.targets() != null && !newVersion.targets().isEmpty()) {
            String insertTargetSql = """
                    INSERT INTO fitness.goal_targets (
                        id, goal_version_id, metric_definition_id, exercise_variation_id,
                        start_value, target_value, target_min_value, target_max_value,
                        unit_id, target_repetitions, target_date, notes, created_at
                    ) VALUES (
                        ?, ?, ?, ?,
                        ?, ?, ?, ?,
                        ?, ?, ?, ?, now()
                    )
                    """;
            for (GoalTarget tgt : newVersion.targets()) {
                UUID tgtId = tgt.id() != null ? tgt.id() : UUID.randomUUID();
                jdbcTemplate.update(
                        insertTargetSql,
                        tgtId,
                        newVersionId,
                        tgt.metricDefinitionId(),
                        tgt.exerciseVariationId(),
                        tgt.startValue(),
                        tgt.targetValue(),
                        tgt.targetMinValue(),
                        tgt.targetMaxValue(),
                        tgt.unitId(),
                        tgt.targetRepetitions(),
                        tgt.targetDate() != null ? Date.valueOf(tgt.targetDate()) : null,
                        tgt.notes()
                );
            }
        }

        // 5. Lock the new version with APPROVED
        String lockVersionSql = """
                UPDATE fitness.fitness_goal_versions
                SET locked_at = now(),
                    locked_by = ?,
                    lock_reason = 'APPROVED'::fitness.version_lock_reason
                WHERE id = ?
                """;
        jdbcTemplate.update(lockVersionSql, studentId, newVersionId);

        // 6. Update fitness_goals title and updated_at
        if (newVersion.title() != null && !newVersion.title().isBlank()) {
            String updateGoalSql = """
                    UPDATE fitness.fitness_goals
                    SET title = ?, updated_at = now()
                    WHERE id = ?
                    """;
            jdbcTemplate.update(updateGoalSql, newVersion.title(), goalId);
        } else {
            String updateGoalSql = """
                    UPDATE fitness.fitness_goals
                    SET updated_at = now()
                    WHERE id = ?
                    """;
            jdbcTemplate.update(updateGoalSql, goalId);
        }

        // 7. Load and return newly created version
        return findVersionById(goalId, newVersionId)
                .orElseThrow(() -> new IllegalStateException("Failed to load newly created goal version: " + newVersionId));
    }

    private List<GoalObjective> loadObjectives(UUID versionId) {
        String sql = """
                SELECT o.id, o.goal_version_id, o.goal_type_id,
                       gt.code as goal_type_code, gt.name as goal_type_name,
                       o.priority, o.sort_order, o.notes
                FROM fitness.goal_objectives o
                JOIN fitness.goal_types gt ON gt.id = o.goal_type_id
                WHERE o.goal_version_id = ?
                ORDER BY o.sort_order ASC, o.id ASC
                """;
        return jdbcTemplate.query(sql, OBJECTIVE_ROW_MAPPER, versionId);
    }

    private List<GoalTarget> loadTargets(UUID versionId) {
        String sql = """
                SELECT t.id, t.goal_version_id, t.metric_definition_id,
                       md.code as metric_code, md.display_name as metric_display_name,
                       t.exercise_variation_id, t.start_value, t.target_value,
                       t.target_min_value, t.target_max_value,
                       t.unit_id, u.code as unit_code, u.symbol as unit_symbol,
                       t.target_repetitions, t.target_date, t.notes, t.created_at
                FROM fitness.goal_targets t
                JOIN fitness.metric_definitions md ON md.id = t.metric_definition_id
                JOIN fitness.measurement_units u ON u.id = t.unit_id
                WHERE t.goal_version_id = ?
                ORDER BY t.created_at ASC, t.id ASC
                """;
        return jdbcTemplate.query(sql, TARGET_ROW_MAPPER, versionId);
    }
}
