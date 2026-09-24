package com.fitnesscoaching.platform.modules.goal.adapter.out.persistence;

import com.fitnesscoaching.platform.common.exception.GoalProposalAlreadyDecidedException;
import com.fitnesscoaching.platform.common.exception.StaleGoalProposalException;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalProposalPort;
import com.fitnesscoaching.platform.modules.goal.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
public class GoalProposalPersistenceAdapter implements GoalProposalPort {

    private final JdbcTemplate jdbcTemplate;

    public GoalProposalPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<GoalProposalObjective> OBJECTIVE_ROW_MAPPER = (rs, rowNum) -> {
        UUID id = (UUID) rs.getObject("id");
        UUID proposalId = (UUID) rs.getObject("goal_proposal_id");
        short goalTypeId = rs.getShort("goal_type_id");
        String goalTypeCode = rs.getString("goal_type_code");
        String goalTypeName = rs.getString("goal_type_name");
        String priorityStr = rs.getString("priority");
        ObjectivePriority priority = priorityStr != null ? ObjectivePriority.valueOf(priorityStr) : ObjectivePriority.SECONDARY;
        int sortOrder = rs.getInt("sort_order");
        String notes = rs.getString("notes");

        return new GoalProposalObjective(
                id,
                proposalId,
                goalTypeId,
                goalTypeCode,
                goalTypeName,
                priority,
                sortOrder,
                notes
        );
    };

    private static final RowMapper<GoalProposalTarget> TARGET_ROW_MAPPER = (rs, rowNum) -> {
        UUID id = (UUID) rs.getObject("id");
        UUID proposalId = (UUID) rs.getObject("goal_proposal_id");
        int metricDefId = rs.getInt("metric_definition_id");
        String metricCode = rs.getString("metric_code");
        String metricDisplayName = rs.getString("metric_display_name");
        UUID exerciseVariationId = (UUID) rs.getObject("exercise_variation_id");
        BigDecimal startValue = rs.getBigDecimal("start_value");
        BigDecimal targetValue = rs.getBigDecimal("target_value");
        BigDecimal targetMinValue = rs.getBigDecimal("target_min_value");
        BigDecimal targetMaxValue = rs.getBigDecimal("target_max_value");
        short unitId = rs.getShort("unit_id");
        String unitCode = rs.getString("unit_code");
        String unitSymbol = rs.getString("unit_symbol");
        int reps = rs.getInt("target_repetitions");
        Integer targetRepetitions = rs.wasNull() ? null : reps;
        Date targetDt = rs.getDate("target_date");
        LocalDate targetDate = targetDt != null ? targetDt.toLocalDate() : null;
        String notes = rs.getString("notes");

        return new GoalProposalTarget(
                id,
                proposalId,
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
                notes
        );
    };

    @Override
    @Transactional
    public GoalProposal saveProposal(GoalProposal proposal) {
        UUID proposalId = proposal.id() != null ? proposal.id() : UUID.randomUUID();

        String insertProposalSql = """
                INSERT INTO fitness.goal_proposals (
                    id, student_id, fitness_goal_id, base_goal_version_id,
                    source, created_by, proposed_title,
                    proposed_start_date, proposed_target_date, proposed_duration_days,
                    reason, status, expires_at, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?,
                    ?::fitness.proposal_source, ?, ?,
                    ?, ?, ?,
                    ?, ?::fitness.proposal_status, ?, now(), now()
                )
                """;
        jdbcTemplate.update(insertProposalSql,
                proposalId,
                proposal.studentId(),
                proposal.fitnessGoalId(),
                proposal.baseGoalVersionId(),
                proposal.source().name(),
                proposal.createdBy(),
                proposal.proposedTitle(),
                proposal.proposedStartDate(),
                proposal.proposedTargetDate(),
                proposal.proposedDurationDays(),
                proposal.reason(),
                proposal.status().name(),
                proposal.expiresAt() != null ? Timestamp.from(proposal.expiresAt()) : null
        );

        String insertObjectiveSql = """
                INSERT INTO fitness.goal_proposal_objectives (
                    id, goal_proposal_id, goal_type_id, priority, sort_order, notes
                ) VALUES (?, ?, ?, ?::fitness.objective_priority, ?, ?)
                """;
        for (GoalProposalObjective obj : proposal.objectives()) {
            UUID objId = obj.id() != null ? obj.id() : UUID.randomUUID();
            jdbcTemplate.update(insertObjectiveSql,
                    objId,
                    proposalId,
                    obj.goalTypeId(),
                    obj.priority().name(),
                    obj.sortOrder(),
                    obj.notes()
            );
        }

        String insertTargetSql = """
                INSERT INTO fitness.goal_proposal_targets (
                    id, goal_proposal_id, metric_definition_id, exercise_variation_id,
                    start_value, target_value, target_min_value, target_max_value,
                    unit_id, target_repetitions, target_date, notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        for (GoalProposalTarget tgt : proposal.targets()) {
            UUID tgtId = tgt.id() != null ? tgt.id() : UUID.randomUUID();
            jdbcTemplate.update(insertTargetSql,
                    tgtId,
                    proposalId,
                    tgt.metricDefinitionId(),
                    tgt.exerciseVariationId(),
                    tgt.startValue(),
                    tgt.targetValue(),
                    tgt.targetMinValue(),
                    tgt.targetMaxValue(),
                    tgt.unitId(),
                    tgt.targetRepetitions(),
                    tgt.targetDate(),
                    tgt.notes()
            );
        }

        String insertHistorySql = """
                INSERT INTO fitness.goal_proposal_status_history (
                    id, goal_proposal_id, from_status, to_status, changed_by, reason, changed_at
                ) VALUES (
                    gen_random_uuid(), ?, NULL, ?::fitness.proposal_status, ?, ?, now()
                )
                """;
        jdbcTemplate.update(insertHistorySql,
                proposalId,
                proposal.status().name(),
                proposal.createdBy(),
                proposal.reason()
        );

        return findById(proposalId).orElseThrow(() -> new IllegalStateException("Failed to load saved proposal: " + proposalId));
    }

    @Override
    public Optional<GoalProposal> findById(UUID proposalId) {
        String sql = """
                SELECT id, student_id, fitness_goal_id, base_goal_version_id,
                       source, created_by, proposed_title,
                       proposed_start_date, proposed_target_date, proposed_duration_days,
                       reason, status, decided_by, decided_at, decision_note,
                       expires_at, created_at, updated_at
                FROM fitness.goal_proposals
                WHERE id = ?
                """;

        List<GoalProposal> proposals = jdbcTemplate.query(sql, (rs, rowNum) -> {
            UUID id = (UUID) rs.getObject("id");
            UUID studentId = (UUID) rs.getObject("student_id");
            UUID fitnessGoalId = (UUID) rs.getObject("fitness_goal_id");
            UUID baseGoalVersionId = (UUID) rs.getObject("base_goal_version_id");
            String sourceStr = rs.getString("source");
            ProposalSource source = sourceStr != null ? ProposalSource.valueOf(sourceStr) : ProposalSource.TRAINER;
            UUID createdBy = (UUID) rs.getObject("created_by");
            String proposedTitle = rs.getString("proposed_title");

            Date pStartDate = rs.getDate("proposed_start_date");
            LocalDate proposedStartDate = pStartDate != null ? pStartDate.toLocalDate() : null;

            Date pTargetDate = rs.getDate("proposed_target_date");
            LocalDate proposedTargetDate = pTargetDate != null ? pTargetDate.toLocalDate() : null;

            int pDur = rs.getInt("proposed_duration_days");
            Integer proposedDurationDays = rs.wasNull() ? null : pDur;

            String reason = rs.getString("reason");
            String statusStr = rs.getString("status");
            ProposalStatus status = statusStr != null ? ProposalStatus.valueOf(statusStr) : ProposalStatus.PENDING;

            UUID decidedBy = (UUID) rs.getObject("decided_by");
            Timestamp decTs = rs.getTimestamp("decided_at");
            Instant decidedAt = decTs != null ? decTs.toInstant() : null;

            String decisionNote = rs.getString("decision_note");

            Timestamp expTs = rs.getTimestamp("expires_at");
            Instant expiresAt = expTs != null ? expTs.toInstant() : null;

            Timestamp crTs = rs.getTimestamp("created_at");
            Instant createdAt = crTs != null ? crTs.toInstant() : null;

            Timestamp upTs = rs.getTimestamp("updated_at");
            Instant updatedAt = upTs != null ? upTs.toInstant() : null;

            return new GoalProposal(
                    id,
                    studentId,
                    fitnessGoalId,
                    baseGoalVersionId,
                    source,
                    createdBy,
                    proposedTitle,
                    proposedStartDate,
                    proposedTargetDate,
                    proposedDurationDays,
                    reason,
                    status,
                    decidedBy,
                    decidedAt,
                    decisionNote,
                    expiresAt,
                    createdAt,
                    updatedAt,
                    List.of(),
                    List.of(),
                    null
            );
        }, proposalId);

        if (proposals.isEmpty()) {
            return Optional.empty();
        }

        GoalProposal p = proposals.get(0);
        List<GoalProposalObjective> objectives = loadProposalObjectives(p.id());
        List<GoalProposalTarget> targets = loadProposalTargets(p.id());
        FitnessGoalVersion baseVersion = p.baseGoalVersionId() != null
                ? loadBaseVersion(p.baseGoalVersionId()).orElse(null)
                : null;

        return Optional.of(new GoalProposal(
                p.id(),
                p.studentId(),
                p.fitnessGoalId(),
                p.baseGoalVersionId(),
                p.source(),
                p.createdBy(),
                p.proposedTitle(),
                p.proposedStartDate(),
                p.proposedTargetDate(),
                p.proposedDurationDays(),
                p.reason(),
                p.status(),
                p.decidedBy(),
                p.decidedAt(),
                p.decisionNote(),
                p.expiresAt(),
                p.createdAt(),
                p.updatedAt(),
                objectives,
                targets,
                baseVersion
        ));
    }

    @Override
    public List<GoalProposal> findByStudentId(UUID studentId, ProposalStatus statusFilter, int limit, long offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, student_id, fitness_goal_id, base_goal_version_id,
                       source, created_by, proposed_title,
                       proposed_start_date, proposed_target_date, proposed_duration_days,
                       reason, status, decided_by, decided_at, decision_note,
                       expires_at, created_at, updated_at
                FROM fitness.goal_proposals
                WHERE student_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(studentId);

        if (statusFilter != null) {
            sql.append(" AND status = ?::fitness.proposal_status");
            params.add(statusFilter.name());
        }

        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        List<GoalProposal> summaries = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            UUID id = (UUID) rs.getObject("id");
            UUID sId = (UUID) rs.getObject("student_id");
            UUID fgId = (UUID) rs.getObject("fitness_goal_id");
            UUID bgvId = (UUID) rs.getObject("base_goal_version_id");
            String sourceStr = rs.getString("source");
            ProposalSource source = sourceStr != null ? ProposalSource.valueOf(sourceStr) : ProposalSource.TRAINER;
            UUID createdBy = (UUID) rs.getObject("created_by");
            String proposedTitle = rs.getString("proposed_title");

            Date pStartDate = rs.getDate("proposed_start_date");
            LocalDate proposedStartDate = pStartDate != null ? pStartDate.toLocalDate() : null;

            Date pTargetDate = rs.getDate("proposed_target_date");
            LocalDate proposedTargetDate = pTargetDate != null ? pTargetDate.toLocalDate() : null;

            int pDur = rs.getInt("proposed_duration_days");
            Integer proposedDurationDays = rs.wasNull() ? null : pDur;

            String reason = rs.getString("reason");
            String statusStr = rs.getString("status");
            ProposalStatus status = statusStr != null ? ProposalStatus.valueOf(statusStr) : ProposalStatus.PENDING;

            UUID decidedBy = (UUID) rs.getObject("decided_by");
            Timestamp decTs = rs.getTimestamp("decided_at");
            Instant decidedAt = decTs != null ? decTs.toInstant() : null;

            String decisionNote = rs.getString("decision_note");

            Timestamp expTs = rs.getTimestamp("expires_at");
            Instant expiresAt = expTs != null ? expTs.toInstant() : null;

            Timestamp crTs = rs.getTimestamp("created_at");
            Instant createdAt = crTs != null ? crTs.toInstant() : null;

            Timestamp upTs = rs.getTimestamp("updated_at");
            Instant updatedAt = upTs != null ? upTs.toInstant() : null;

            return new GoalProposal(
                    id,
                    sId,
                    fgId,
                    bgvId,
                    source,
                    createdBy,
                    proposedTitle,
                    proposedStartDate,
                    proposedTargetDate,
                    proposedDurationDays,
                    reason,
                    status,
                    decidedBy,
                    decidedAt,
                    decisionNote,
                    expiresAt,
                    createdAt,
                    updatedAt,
                    List.of(),
                    List.of(),
                    null
            );
        }, params.toArray());

        if (summaries.isEmpty()) {
            return List.of();
        }

        List<UUID> proposalIds = summaries.stream().map(GoalProposal::id).toList();

        // Batch load objectives to prevent N+1 queries
        String objSql = """
                SELECT o.id, o.goal_proposal_id, o.goal_type_id,
                       gt.code as goal_type_code, gt.name as goal_type_name,
                       o.priority, o.sort_order, o.notes
                FROM fitness.goal_proposal_objectives o
                JOIN fitness.goal_types gt ON gt.id = o.goal_type_id
                WHERE o.goal_proposal_id = ANY (?)
                ORDER BY o.sort_order ASC, o.id ASC
                """;
        Map<UUID, List<GoalProposalObjective>> objectivesByProposal = new HashMap<>();
        jdbcTemplate.query(
                con -> {
                    var ps = con.prepareStatement(objSql);
                    ps.setArray(1, con.createArrayOf("uuid", proposalIds.toArray()));
                    return ps;
                },
                rs -> {
                    UUID pId = (UUID) rs.getObject("goal_proposal_id");
                    GoalProposalObjective obj = OBJECTIVE_ROW_MAPPER.mapRow(rs, 0);
                    objectivesByProposal.computeIfAbsent(pId, k -> new ArrayList<>()).add(obj);
                }
        );

        // Batch load targets to prevent N+1 queries
        String tgtSql = """
                SELECT t.id, t.goal_proposal_id, t.metric_definition_id,
                       md.code as metric_code, md.display_name as metric_display_name,
                       t.exercise_variation_id, t.start_value, t.target_value,
                       t.target_min_value, t.target_max_value,
                       t.unit_id, u.code as unit_code, u.symbol as unit_symbol,
                       t.target_repetitions, t.target_date, t.notes
                FROM fitness.goal_proposal_targets t
                JOIN fitness.metric_definitions md ON md.id = t.metric_definition_id
                JOIN fitness.measurement_units u ON u.id = t.unit_id
                WHERE t.goal_proposal_id = ANY (?)
                ORDER BY t.id ASC
                """;
        Map<UUID, List<GoalProposalTarget>> targetsByProposal = new HashMap<>();
        jdbcTemplate.query(
                con -> {
                    var ps = con.prepareStatement(tgtSql);
                    ps.setArray(1, con.createArrayOf("uuid", proposalIds.toArray()));
                    return ps;
                },
                rs -> {
                    UUID pId = (UUID) rs.getObject("goal_proposal_id");
                    GoalProposalTarget tgt = TARGET_ROW_MAPPER.mapRow(rs, 0);
                    targetsByProposal.computeIfAbsent(pId, k -> new ArrayList<>()).add(tgt);
                }
        );

        List<GoalProposal> result = new ArrayList<>();
        for (GoalProposal p : summaries) {
            List<GoalProposalObjective> objectives = objectivesByProposal.getOrDefault(p.id(), List.of());
            List<GoalProposalTarget> targets = targetsByProposal.getOrDefault(p.id(), List.of());
            result.add(new GoalProposal(
                    p.id(),
                    p.studentId(),
                    p.fitnessGoalId(),
                    p.baseGoalVersionId(),
                    p.source(),
                    p.createdBy(),
                    p.proposedTitle(),
                    p.proposedStartDate(),
                    p.proposedTargetDate(),
                    p.proposedDurationDays(),
                    p.reason(),
                    p.status(),
                    p.decidedBy(),
                    p.decidedAt(),
                    p.decisionNote(),
                    p.expiresAt(),
                    p.createdAt(),
                    p.updatedAt(),
                    objectives,
                    targets,
                    null
            ));
        }

        return result;
    }

    @Override
    public long countByStudentId(UUID studentId, ProposalStatus statusFilter) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM fitness.goal_proposals WHERE student_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(studentId);

        if (statusFilter != null) {
            sql.append(" AND status = ?::fitness.proposal_status");
            params.add(statusFilter.name());
        }

        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return count != null ? count : 0L;
    }

    @Override
    @Transactional
    public boolean rejectProposal(UUID proposalId, UUID studentId, String decisionNote) {
        String updateSql = """
                UPDATE fitness.goal_proposals
                SET status = 'REJECTED'::fitness.proposal_status,
                    decided_at = now(),
                    decided_by = ?,
                    decision_note = ?,
                    updated_at = now()
                WHERE id = ? AND status = 'PENDING'::fitness.proposal_status
                """;
        int rows = jdbcTemplate.update(updateSql, studentId, decisionNote, proposalId);
        if (rows == 0) {
            return false;
        }

        String insertHistorySql = """
                INSERT INTO fitness.goal_proposal_status_history (
                    id, goal_proposal_id, from_status, to_status, changed_by, reason, changed_at
                ) VALUES (
                    gen_random_uuid(), ?, 'PENDING'::fitness.proposal_status, 'REJECTED'::fitness.proposal_status, ?, ?, now()
                )
                """;
        jdbcTemplate.update(insertHistorySql, proposalId, studentId, decisionNote != null ? decisionNote : "Proposal rejected by student");
        return true;
    }

    @Override
    @Transactional
    public FitnessGoalVersion acceptProposal(GoalProposal proposal, UUID studentId, String decisionNote) {
        // 1. CAS update proposal from PENDING to ACCEPTED
        String updateProposalSql = """
                UPDATE fitness.goal_proposals
                SET status = 'ACCEPTED'::fitness.proposal_status,
                    decided_at = now(),
                    decided_by = ?,
                    decision_note = ?,
                    updated_at = now()
                WHERE id = ? AND status = 'PENDING'::fitness.proposal_status
                """;
        int proposalUpdated = jdbcTemplate.update(updateProposalSql, studentId, decisionNote, proposal.id());
        if (proposalUpdated == 0) {
            throw new GoalProposalAlreadyDecidedException("Goal proposal has already been decided or is not pending");
        }

        // 2. CAS close current version (effective_until = now())
        String closeBaseVersionSql = """
                UPDATE fitness.fitness_goal_versions
                SET effective_until = now()
                WHERE id = ? AND effective_until IS NULL
                """;
        int versionClosed = jdbcTemplate.update(closeBaseVersionSql, proposal.baseGoalVersionId());
        if (versionClosed == 0) {
            throw new StaleGoalProposalException("Base goal version is no longer active; cannot accept stale proposal");
        }

        // 3. Query base version details
        FitnessGoalVersion baseVersion = proposal.baseVersion() != null
                ? proposal.baseVersion()
                : loadBaseVersion(proposal.baseGoalVersionId()).orElseThrow(() -> new StaleGoalProposalException("Base goal version not found"));

        int nextVersionNumber = baseVersion.versionNumber() + 1;
        String versionTitle = proposal.proposedTitle() != null && !proposal.proposedTitle().isBlank()
                ? proposal.proposedTitle()
                : baseVersion.title();
        LocalDate startDate = proposal.proposedStartDate() != null
                ? proposal.proposedStartDate()
                : baseVersion.startDate();
        LocalDate targetDate = proposal.proposedTargetDate() != null
                ? proposal.proposedTargetDate()
                : baseVersion.targetDate();
        Integer durationDays = proposal.proposedDurationDays() != null
                ? proposal.proposedDurationDays()
                : baseVersion.durationDays();

        // 4. Update fitness_goals title if proposed title is provided and differs
        if (proposal.proposedTitle() != null && !proposal.proposedTitle().isBlank()) {
            String updateGoalTitleSql = """
                    UPDATE fitness.fitness_goals
                    SET title = ?, updated_at = now()
                    WHERE id = ?
                    """;
            jdbcTemplate.update(updateGoalTitleSql, proposal.proposedTitle(), proposal.fitnessGoalId());
        }

        // 5. Insert new version (with lock fields NULL initially to satisfy trigger)
        UUID newVersionId = UUID.randomUUID();
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
        String changeReason;
        String changeSummary;
        if (proposal.reason() != null && proposal.reason().length() <= 100) {
            changeReason = proposal.reason();
            changeSummary = "Accepted proposal " + proposal.id();
        } else {
            changeReason = "ACCEPTED_PROPOSAL";
            changeSummary = proposal.reason() != null
                    ? "Accepted proposal " + proposal.id() + ": " + proposal.reason()
                    : "Accepted proposal " + proposal.id();
        }

        jdbcTemplate.update(insertVersionSql,
                newVersionId,
                proposal.fitnessGoalId(),
                nextVersionNumber,
                versionTitle,
                startDate,
                targetDate,
                durationDays,
                changeReason,
                changeSummary,
                studentId,
                proposal.id()
        );

        // 6. Insert new objectives into fitness.goal_objectives (copying notes!)
        String insertObjSql = """
                INSERT INTO fitness.goal_objectives (
                    id, goal_version_id, goal_type_id, priority, sort_order, notes
                ) VALUES (?, ?, ?, ?::fitness.objective_priority, ?, ?)
                """;
        for (GoalProposalObjective obj : proposal.objectives()) {
            jdbcTemplate.update(insertObjSql,
                    UUID.randomUUID(),
                    newVersionId,
                    obj.goalTypeId(),
                    obj.priority().name(),
                    obj.sortOrder(),
                    obj.notes()
            );
        }

        // 7. Insert new targets into fitness.goal_targets
        String insertTgtSql = """
                INSERT INTO fitness.goal_targets (
                    id, goal_version_id, metric_definition_id, exercise_variation_id,
                    start_value, target_value, target_min_value, target_max_value,
                    unit_id, target_repetitions, target_date, notes, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """;
        for (GoalProposalTarget tgt : proposal.targets()) {
            jdbcTemplate.update(insertTgtSql,
                    UUID.randomUUID(),
                    newVersionId,
                    tgt.metricDefinitionId(),
                    tgt.exerciseVariationId(),
                    tgt.startValue(),
                    tgt.targetValue(),
                    tgt.targetMinValue(),
                    tgt.targetMaxValue(),
                    tgt.unitId(),
                    tgt.targetRepetitions(),
                    tgt.targetDate(),
                    tgt.notes()
            );
        }

        // 8. Lock the new version with APPROVED
        String lockVersionSql = """
                UPDATE fitness.fitness_goal_versions
                SET locked_at = now(),
                    locked_by = ?,
                    lock_reason = 'APPROVED'::fitness.version_lock_reason
                WHERE id = ?
                """;
        jdbcTemplate.update(lockVersionSql, studentId, newVersionId);

        // 9. Record proposal status history
        String insertProposalHistorySql = """
                INSERT INTO fitness.goal_proposal_status_history (
                    id, goal_proposal_id, from_status, to_status, changed_by, reason, changed_at
                ) VALUES (
                    gen_random_uuid(), ?, 'PENDING'::fitness.proposal_status, 'ACCEPTED'::fitness.proposal_status, ?, ?, now()
                )
                """;
        jdbcTemplate.update(insertProposalHistorySql, proposal.id(), studentId, decisionNote != null ? decisionNote : "Proposal accepted by student");

        // 10. Load and return newly created version
        return loadBaseVersion(newVersionId).orElseThrow(() -> new IllegalStateException("Failed to load newly created goal version: " + newVersionId));
    }

    private List<GoalProposalObjective> loadProposalObjectives(UUID proposalId) {
        String sql = """
                SELECT o.id, o.goal_proposal_id, o.goal_type_id,
                       gt.code as goal_type_code, gt.name as goal_type_name,
                       o.priority, o.sort_order, o.notes
                FROM fitness.goal_proposal_objectives o
                JOIN fitness.goal_types gt ON gt.id = o.goal_type_id
                WHERE o.goal_proposal_id = ?
                ORDER BY o.sort_order ASC, o.id ASC
                """;
        return jdbcTemplate.query(sql, OBJECTIVE_ROW_MAPPER, proposalId);
    }

    private List<GoalProposalTarget> loadProposalTargets(UUID proposalId) {
        String sql = """
                SELECT t.id, t.goal_proposal_id, t.metric_definition_id,
                       md.code as metric_code, md.display_name as metric_display_name,
                       t.exercise_variation_id, t.start_value, t.target_value,
                       t.target_min_value, t.target_max_value,
                       t.unit_id, u.code as unit_code, u.symbol as unit_symbol,
                       t.target_repetitions, t.target_date, t.notes
                FROM fitness.goal_proposal_targets t
                JOIN fitness.metric_definitions md ON md.id = t.metric_definition_id
                JOIN fitness.measurement_units u ON u.id = t.unit_id
                WHERE t.goal_proposal_id = ?
                ORDER BY t.id ASC
                """;
        return jdbcTemplate.query(sql, TARGET_ROW_MAPPER, proposalId);
    }

    private Optional<FitnessGoalVersion> loadBaseVersion(UUID versionId) {
        String versionSql = """
                SELECT id, fitness_goal_id, version_number, title, start_date, target_date,
                       duration_days, effective_from, effective_until, resume_date,
                       change_reason, change_summary, created_by, source_proposal_id,
                       created_at, locked_at, locked_by, lock_reason
                FROM fitness.fitness_goal_versions
                WHERE id = ?
                """;
        List<FitnessGoalVersion> versions = jdbcTemplate.query(versionSql, (rs, rowNum) -> {
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
        }, versionId);

        if (versions.isEmpty()) {
            return Optional.empty();
        }

        FitnessGoalVersion v = versions.get(0);
        List<GoalObjective> objectives = loadVersionObjectives(v.id());
        List<GoalTarget> targets = loadVersionTargets(v.id());

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

    private List<GoalObjective> loadVersionObjectives(UUID versionId) {
        String sql = """
                SELECT o.id, o.goal_version_id, o.goal_type_id,
                       gt.code as goal_type_code, gt.name as goal_type_name,
                       o.priority, o.sort_order, o.notes
                FROM fitness.goal_objectives o
                JOIN fitness.goal_types gt ON gt.id = o.goal_type_id
                WHERE o.goal_version_id = ?
                ORDER BY o.sort_order ASC, o.id ASC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
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
        }, versionId);
    }

    private List<GoalTarget> loadVersionTargets(UUID versionId) {
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
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            UUID id = (UUID) rs.getObject("id");
            UUID goalVersionId = (UUID) rs.getObject("goal_version_id");
            int metricDefId = rs.getInt("metric_definition_id");
            String metricCode = rs.getString("metric_code");
            String metricDisplayName = rs.getString("metric_display_name");
            UUID exerciseVariationId = (UUID) rs.getObject("exercise_variation_id");
            BigDecimal startValue = rs.getBigDecimal("start_value");
            BigDecimal targetValue = rs.getBigDecimal("target_value");
            BigDecimal targetMinValue = rs.getBigDecimal("target_min_value");
            BigDecimal targetMaxValue = rs.getBigDecimal("target_max_value");
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
        }, versionId);
    }
}
