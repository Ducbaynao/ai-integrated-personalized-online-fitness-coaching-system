package com.fitnesscoaching.platform.modules.nutrition;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementUnitReferenceQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Nutrition-owned strategic decisions. A Trainer can only propose; the Student publishes. */
@Service
public class NutritionStrategyService {
    private final JdbcTemplate jdbc;
    private final NutritionStrategyCommands commands;
    private final NutritionTargetDayService days;
    private final CoachingAuthorityQuery coaching;
    private final MeasurementUnitReferenceQuery units;

    public NutritionStrategyService(JdbcTemplate jdbc, NutritionStrategyCommands commands,
                                    NutritionTargetDayService days, CoachingAuthorityQuery coaching,
                                    MeasurementUnitReferenceQuery units) {
        this.jdbc = jdbc; this.commands = commands; this.days = days; this.coaching = coaching;
        this.units = units;
    }

    public record GoalType(int id, String code, String name) {}
    public record TargetNutrient(int id, String code, String name, int defaultUnitId,
                                 String unitCode, String unitSymbol, String dimension) {}
    public record TargetSpec(int nutrientId, int unitId, String dayType, BigDecimal targetAmount) {}
    public record ChangeInput(String kind, UUID goalId, Long expectedGoalVersion, UUID baseVersionId,
                              String title, Integer goalTypeId, String timezone, LocalDate effectiveFrom,
                              List<TargetSpec> targets, boolean confirmed, String commandKey) {}
    public record TransitionInput(String action, long expectedGoalVersion, String reason, String commandKey) {}
    public record TargetVersion(UUID id, int versionNumber, LocalDate effectiveFrom,
                                LocalDate effectiveUntil, String dateSemantics, String timezone,
                                Integer goalTypeId, List<TargetSpec> targets) {}
    public record Goal(UUID id, UUID studentId, String title, String status, long aggregateVersion,
                       String timezone, List<TargetVersion> versions) {}
    public record State(Goal goal, List<Proposal> proposals) {}
    public record DecisionInput(String decision, long expectedProposalVersion, String commandKey) {}
    public record Proposal(UUID id, UUID studentId, UUID trainerId, String kind, String status,
                           long proposalVersion, UUID goalId, UUID baseVersionId, Long baseGoalVersion,
                           String title, int goalTypeId, String timezone, LocalDate effectiveFrom,
                           String reason, List<TargetSpec> targets, Instant createdAt, Instant decidedAt,
                           UUID decidedGoalId, UUID decidedGoalVersionId) {}

    private record GoalRow(UUID id, UUID student, String title, String status, long version,
                           String timezone, boolean strategyManaged) {}
    private record OpenVersion(UUID id, int number, LocalDate from) {}
    private record ProposalRow(UUID id, UUID student, UUID trainer, UUID period, String kind,
                               String status, long version, UUID goalId, UUID baseVersionId,
                               Long baseGoalVersion, String title, int goalTypeId, String timezone,
                               LocalDate effectiveFrom, String reason, Instant createdAt,
                               Instant decidedAt, UUID decidedGoalId, UUID decidedGoalVersionId) {}

    @Transactional(readOnly = true)
    public List<GoalType> goalTypes() {
        return jdbc.query("""
                SELECT id,code,name FROM fitness.nutrition_goal_types WHERE is_active ORDER BY id
                """, (rs, n) -> new GoalType(rs.getInt(1), rs.getString(2), rs.getString(3)));
    }

    @Transactional(readOnly = true)
    public List<TargetNutrient> targetNutrients() {
        record NutrientRow(int id, String code, String name, short unitId) {}
        List<NutrientRow> rows = jdbc.query("""
                SELECT id,code,name,default_unit_id FROM fitness.nutrients
                ORDER BY id
                """, (rs, n) -> new NutrientRow(rs.getInt(1), rs.getString(2),
                rs.getString(3), rs.getShort(4)));
        var references = units.resolveAll(rows.stream().map(NutrientRow::unitId).toList());
        return rows.stream().filter(row -> references.containsKey(row.unitId()))
                .map(row -> {
                    var unit = references.get(row.unitId());
                    return new TargetNutrient(row.id(), row.code(), row.name(), row.unitId(),
                            unit.code(), unit.symbol(), unit.dimension());
                }).toList();
    }

    @Transactional(readOnly = true)
    public State mine(UUID actor) {
        commands.requireStudent(actor);
        GoalRow row = jdbc.query("""
                SELECT id,student_id,title,status::text,aggregate_version,calendar_timezone,strategy_managed
                FROM fitness.nutrition_goals WHERE student_id=? AND deleted_at IS NULL
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'PAUSED' THEN 1 ELSE 2 END,
                         created_at DESC,id DESC LIMIT 1
                """, (rs, n) -> goalRow(rs), actor).stream().findFirst().orElse(null);
        return new State(row == null ? null : presentGoal(row), proposalsForStudent(actor));
    }

    @Transactional
    public State forTrainer(UUID trainer, UUID student) {
        requireTrainer(trainer, student, DataAccessLevel.VIEW);
        GoalRow row = jdbc.query("""
                SELECT id,student_id,title,status::text,aggregate_version,calendar_timezone,strategy_managed
                FROM fitness.nutrition_goals WHERE student_id=? AND deleted_at IS NULL
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'PAUSED' THEN 1 ELSE 2 END,
                         created_at DESC,id DESC LIMIT 1
                """, (rs, n) -> goalRow(rs), student).stream().findFirst().orElse(null);
        return new State(row == null ? null : presentGoal(row), proposalsForTrainer(trainer, student));
    }

    @Transactional
    public Goal confirmChange(UUID actor, ChangeInput input) {
        commands.requireStudent(actor);
        validateChange(input, true);
        commands.lockStudent(actor);
        return commands.receipt(actor, input.commandKey(), "NUTRITION_STRATEGY_CHANGE", input,
                Goal.class, () -> applyChange(actor, input, null));
    }

    @Transactional
    public Goal transition(UUID actor, UUID goalId, TransitionInput input) {
        commands.requireStudent(actor);
        if (input == null || !List.of("PAUSE", "RESUME", "END").contains(input.action())
                || input.expectedGoalVersion() < 0 || input.reason() == null || input.reason().isBlank()
                || input.reason().length() > 1000) throw fail("VALIDATION_FAILED", 400);
        commands.lockStudent(actor);
        return commands.receipt(actor, input.commandKey(), "NUTRITION_GOAL_TRANSITION",
                List.of(goalId, input), Goal.class, () -> {
                    GoalRow goal = ownedGoal(actor, goalId, true);
                    if (goal.version() != input.expectedGoalVersion()) throw fail("NUTRITION_VERSION_STALE", 409);
                    String next = switch (input.action()) {
                        case "PAUSE" -> "ACTIVE".equals(goal.status()) ? "PAUSED" : null;
                        case "RESUME" -> "PAUSED".equals(goal.status()) ? "ACTIVE" : null;
                        case "END" -> List.of("ACTIVE", "PAUSED").contains(goal.status()) ? "ENDED" : null;
                        default -> null;
                    };
                    if (next == null) throw fail("NUTRITION_GOAL_STATE_CONFLICT", 409);
                    if ("ACTIVE".equals(next)) requireNoOtherActive(actor, goalId);
                    jdbc.update("""
                            UPDATE fitness.nutrition_goals SET status=?::fitness.lifecycle_status,
                              aggregate_version=aggregate_version+1,updated_at=clock_timestamp(),
                              ended_at=CASE WHEN ?='ENDED' THEN clock_timestamp() ELSE ended_at END
                            WHERE id=? AND aggregate_version=?
                            """, next, next, goalId, goal.version());
                    statusHistory(goalId, goal.status(), next, actor, input.reason().trim());
                    commands.audit(actor, "STUDENT", "NUTRITION_GOAL_" + input.action(),
                            "NUTRITION_GOAL", goalId, Map.of("reason", input.reason().trim()));
                    return presentGoal(ownedGoal(actor, goalId, false));
                });
    }

    @Transactional
    public Proposal propose(UUID trainer, UUID student, ChangeInput input, String reason) {
        commands.lockStudent(student);
        var authority = requireTrainer(trainer, student, DataAccessLevel.CONTRIBUTE);
        validateChange(input, false);
        if (input.confirmed() || reason == null || reason.isBlank() || reason.length() > 2000)
            throw fail("VALIDATION_FAILED", 400);
        return commands.receipt(trainer, input.commandKey(), "NUTRITION_STRATEGY_PROPOSE",
                List.of(student, input, reason), Proposal.class, () -> {
                    if ("NEW_GOAL".equals(input.kind())) requireNoOtherActive(student, null);
                    if ("CHANGE_TARGET".equals(input.kind())) {
                        GoalRow goal = ownedGoal(student, input.goalId(), true);
                        requireBase(goal, input);
                    }
                    UUID proposalId = UUID.randomUUID();
                    jdbc.update("""
                            INSERT INTO fitness.nutrition_proposals
                              (id,student_id,nutrition_goal_id,base_nutrition_goal_version_id,source,
                               created_by,proposed_title,proposed_goal_type_id,proposed_effective_from,
                               reason,proposal_kind,base_goal_aggregate_version,coaching_period_id,
                               calendar_timezone)
                            VALUES (?,?,?,?, 'TRAINER',?,?,?,?,?,?,?,?,?)
                            """, proposalId, student, input.goalId(), input.baseVersionId(), trainer,
                            input.title().trim(), input.goalTypeId(), input.effectiveFrom(), reason.trim(),
                            input.kind(), input.expectedGoalVersion(), authority.periodId(), input.timezone());
                    insertTargets("fitness.nutrition_proposal_targets", "nutrition_proposal_id", proposalId,
                            input.targets());
                    commands.audit(trainer, "TRAINER", "NUTRITION_STRATEGY_PROPOSED",
                            "NUTRITION_PROPOSAL", proposalId, Map.of("studentId", student.toString()));
                    return presentProposal(proposalRow(proposalId));
                });
    }

    @Transactional(readOnly = true)
    public List<Proposal> proposalsMine(UUID actor) {
        commands.requireStudent(actor);
        return proposalsForStudent(actor);
    }

    @Transactional
    public List<Proposal> proposalsForTrainer(UUID trainer, UUID student) {
        requireTrainer(trainer, student, DataAccessLevel.VIEW);
        return jdbc.query("""
                SELECT * FROM fitness.nutrition_proposals
                WHERE student_id=? AND created_by=? AND proposal_kind IS NOT NULL
                ORDER BY created_at DESC,id DESC LIMIT 100
                """, (rs, n) -> presentProposal(proposalRow(rs)), student, trainer);
    }

    @Transactional
    public Proposal proposalDetail(UUID actor, UUID proposalId) {
        ProposalRow row = proposalRow(proposalId);
        if (row == null) throw fail("NUTRITION_PROPOSAL_NOT_FOUND", 404);
        if (actor.equals(row.student())) commands.requireStudent(actor);
        else if (actor.equals(row.trainer())) requireTrainer(actor, row.student(), DataAccessLevel.VIEW);
        else throw fail("NUTRITION_PROPOSAL_NOT_FOUND", 404);
        return presentProposal(row);
    }

    @Transactional
    public Proposal decide(UUID student, UUID proposalId, DecisionInput input) {
        commands.requireStudent(student);
        if (input == null || !List.of("ACCEPT", "REJECT").contains(input.decision())
                || input.expectedProposalVersion() < 0) throw fail("VALIDATION_FAILED", 400);
        commands.lockStudent(student);
        ProposalRow before = proposalRow(proposalId);
        if (before == null || !student.equals(before.student())) throw fail("NUTRITION_PROPOSAL_NOT_FOUND", 404);
        return commands.receipt(student, input.commandKey(), "NUTRITION_PROPOSAL_DECIDE",
                List.of(proposalId, input), Proposal.class, () -> {
                    ProposalRow proposal = proposalRowForUpdate(proposalId);
                    if (!"PENDING".equals(proposal.status())) throw fail("NUTRITION_PROPOSAL_ALREADY_DECIDED", 409);
                    if (proposal.version() != input.expectedProposalVersion())
                        throw fail("NUTRITION_VERSION_STALE", 409);
                    Goal published = null;
                    if ("ACCEPT".equals(input.decision())) {
                        com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision authority;
                        try { authority = requireTrainer(proposal.trainer(), student, DataAccessLevel.CONTRIBUTE); }
                        catch (NutritionFailure lost) { throw fail("NUTRITION_PROPOSAL_STALE", 409); }
                        if (!authority.periodId().equals(proposal.period()))
                            throw fail("NUTRITION_PROPOSAL_STALE", 409);
                        ChangeInput change = new ChangeInput(proposal.kind(), proposal.goalId(),
                                proposal.baseGoalVersion(), proposal.baseVersionId(), proposal.title(),
                                proposal.goalTypeId(), proposal.timezone(), proposal.effectiveFrom(),
                                proposalTargets(proposal.id()), true, input.commandKey());
                        validateChange(change, true);
                        try { published = applyChange(student, change, proposalId); }
                        catch (NutritionFailure stale) {
                            if (stale.status() == 409 || stale.status() == 404)
                                throw fail("NUTRITION_PROPOSAL_STALE", 409);
                            throw stale;
                        }
                    }
                    UUID decidedGoalId = published == null ? null : published.id();
                    UUID decidedVersionId = published == null ? null : published.versions().getLast().id();
                    jdbc.update("""
                            UPDATE fitness.nutrition_proposals
                            SET status=?::fitness.proposal_status,decided_by=?,decided_at=clock_timestamp(),
                                proposal_version=proposal_version+1,decided_goal_id=?,decided_goal_version_id=?
                            WHERE id=? AND status='PENDING' AND proposal_version=?
                            """, "ACCEPT".equals(input.decision()) ? "ACCEPTED" : "REJECTED",
                            student, decidedGoalId, decidedVersionId, proposalId, proposal.version());
                    commands.audit(student, "STUDENT", "NUTRITION_PROPOSAL_" + input.decision(),
                            "NUTRITION_PROPOSAL", proposalId, Map.of("decision", input.decision()));
                    return presentProposal(proposalRow(proposalId));
                });
    }

    private Goal applyChange(UUID student, ChangeInput input, UUID sourceProposal) {
        LocalDate today = commands.databaseNow().atZone(NutritionTargetDayService.validZone(input.timezone()))
                .toLocalDate();
        if (input.effectiveFrom().isBefore(today)) throw fail("VALIDATION_FAILED", 400);
        GoalRow goal;
        int versionNumber;
        if ("NEW_GOAL".equals(input.kind())) {
            requireNoOtherActive(student, null);
            if (input.effectiveFrom().equals(today) && frozen(student, today))
                throw fail("NUTRITION_TARGET_DAY_FROZEN", 409);
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.nutrition_goals
                      (id,student_id,title,status,created_by,calendar_timezone,strategy_managed,activated_at)
                    VALUES (?,?,?,'DRAFT',?,?,true,clock_timestamp())
                    """, id, student, input.title().trim(), student, input.timezone());
            goal = ownedGoal(student, id, false); versionNumber = 1;
        } else {
            goal = ownedGoal(student, input.goalId(), true);
            requireBase(goal, input);
            if (!"ACTIVE".equals(goal.status())) throw fail("NUTRITION_GOAL_STATE_CONFLICT", 409);
            if (!input.effectiveFrom().isAfter(today)) throw fail("VALIDATION_FAILED", 400);
            OpenVersion open = openVersion(goal.id());
            if (!input.effectiveFrom().isAfter(open.from())) throw fail("NUTRITION_TARGET_VERSION_STALE", 409);
            versionNumber = open.number() + 1;
            jdbc.update("""
                    UPDATE fitness.nutrition_goal_versions SET effective_until=?
                    WHERE id=? AND effective_until IS NULL
                    """, input.effectiveFrom(), open.id());
        }
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.nutrition_goal_versions
                  (id,nutrition_goal_id,version_number,effective_from,created_by,
                   nutrition_goal_type_id,date_semantics,calendar_timezone,source_proposal_id)
                VALUES (?,?,?,?,?,?,'HALF_OPEN',?,?)
                """, versionId, goal.id(), versionNumber, input.effectiveFrom(), student,
                input.goalTypeId(), input.timezone(), sourceProposal);
        insertTargets("fitness.daily_nutrition_targets", "nutrition_goal_version_id", versionId, input.targets());
        jdbc.update("""
                UPDATE fitness.nutrition_goal_versions SET locked_at=clock_timestamp(),locked_by=?,lock_reason='APPROVED'
                WHERE id=?
                """, student, versionId);
        jdbc.update("""
                UPDATE fitness.nutrition_goals SET status='ACTIVE',title=?,calendar_timezone=?,
                  aggregate_version=aggregate_version+1,updated_at=clock_timestamp()
                WHERE id=?
                """, input.title().trim(), input.timezone(), goal.id());
        if ("NEW_GOAL".equals(input.kind())) statusHistory(goal.id(), "DRAFT", "ACTIVE", student,
                "STUDENT_CONFIRMED_NEW_GOAL");
        commands.audit(student, "STUDENT", "NUTRITION_" + input.kind() + "_CONFIRMED",
                "NUTRITION_GOAL", goal.id(), Map.of("versionId", versionId.toString(),
                        "effectiveFrom", input.effectiveFrom().toString()));
        return presentGoal(ownedGoal(student, goal.id(), false));
    }

    private void validateChange(ChangeInput input, boolean requireConfirmation) {
        if (input == null || !List.of("NEW_GOAL", "CHANGE_TARGET").contains(input.kind())
                || requireConfirmation && !input.confirmed()
                || input.title() == null || input.title().isBlank() || input.title().length() > 200
                || input.goalTypeId() == null || input.goalTypeId() <= 0 || input.effectiveFrom() == null
                || input.targets() == null || input.targets().isEmpty()
                || "NEW_GOAL".equals(input.kind()) && (input.goalId() != null
                        || input.baseVersionId() != null || input.expectedGoalVersion() != null)
                || "CHANGE_TARGET".equals(input.kind()) && (input.goalId() == null
                        || input.baseVersionId() == null || input.expectedGoalVersion() == null
                        || input.expectedGoalVersion() < 0)) throw fail("VALIDATION_FAILED", 400);
        NutritionTargetDayService.validZone(input.timezone());
        Boolean goalTypeValid = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.nutrition_goal_types WHERE id=? AND is_active)
                """, Boolean.class, input.goalTypeId());
        if (!Boolean.TRUE.equals(goalTypeValid)) throw fail("VALIDATION_FAILED", 400);
        Set<String> seen = new HashSet<>();
        for (TargetSpec target : input.targets()) {
            if (target == null || target.nutrientId() <= 0 || target.unitId() <= 0
                    || !List.of("DEFAULT", "TRAINING_DAY", "REST_DAY").contains(target.dayType())
                    || target.targetAmount() == null || target.targetAmount().signum() < 0
                    || target.targetAmount().scale() > 6 || target.targetAmount().precision() > 18
                    || !seen.add(target.nutrientId() + ":" + target.dayType()))
                throw fail("VALIDATION_FAILED", 400);
            days.validateUnit(target.nutrientId(), target.unitId());
        }
    }

    private void requireBase(GoalRow goal, ChangeInput input) {
        if (!goal.strategyManaged()) throw fail("NUTRITION_LEGACY_TARGET_UNRESOLVED", 409);
        if (goal.version() != input.expectedGoalVersion()
                || !openVersion(goal.id()).id().equals(input.baseVersionId()))
            throw fail("NUTRITION_TARGET_VERSION_STALE", 409);
    }

    private void requireNoOtherActive(UUID student, UUID except) {
        Boolean active = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.nutrition_goals
                WHERE student_id=? AND status='ACTIVE' AND deleted_at IS NULL AND id IS DISTINCT FROM ?)
                """, Boolean.class, student, except);
        if (Boolean.TRUE.equals(active)) throw fail("NUTRITION_ACTIVE_GOAL_EXISTS", 409);
    }

    private boolean frozen(UUID student, LocalDate day) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.nutrition_target_day_snapshots
                    WHERE student_id=? AND target_date=?) OR EXISTS
                    (SELECT 1 FROM fitness.resolved_daily_nutrition_targets
                     WHERE student_id=? AND target_date=?)
                """, Boolean.class, student, day, student, day));
    }

    private GoalRow ownedGoal(UUID student, UUID goalId, boolean lock) {
        if (goalId == null) throw fail("NUTRITION_GOAL_NOT_FOUND", 404);
        String sql = """
                SELECT id,student_id,title,status::text,aggregate_version,calendar_timezone,strategy_managed
                FROM fitness.nutrition_goals WHERE id=? AND student_id=? AND deleted_at IS NULL
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, n) -> goalRow(rs), goalId, student)
                .stream().findFirst().orElseThrow(() -> fail("NUTRITION_GOAL_NOT_FOUND", 404));
    }

    private OpenVersion openVersion(UUID goalId) {
        return jdbc.query("""
                SELECT id,version_number,effective_from FROM fitness.nutrition_goal_versions
                WHERE nutrition_goal_id=? AND effective_until IS NULL FOR UPDATE
                """, (rs, n) -> new OpenVersion((UUID) rs.getObject(1), rs.getInt(2),
                rs.getObject(3, LocalDate.class)), goalId)
                .stream().findFirst().orElseThrow(() -> fail("NUTRITION_TARGET_VERSION_STALE", 409));
    }

    private Goal presentGoal(GoalRow row) {
        List<TargetVersion> versions = jdbc.query("""
                SELECT id,version_number,effective_from,effective_until,date_semantics,
                       calendar_timezone,nutrition_goal_type_id
                FROM fitness.nutrition_goal_versions WHERE nutrition_goal_id=?
                ORDER BY version_number
                """, (rs, n) -> new TargetVersion((UUID) rs.getObject(1), rs.getInt(2),
                rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class), rs.getString(5),
                rs.getString(6), (Integer) rs.getObject(7),
                targetSpecs("fitness.daily_nutrition_targets", "nutrition_goal_version_id",
                        (UUID) rs.getObject(1))), row.id());
        return new Goal(row.id(), row.student(), row.title(), row.status(), row.version(),
                row.timezone(), versions);
    }

    private void insertTargets(String table, String foreignKey, UUID parentId, List<TargetSpec> targets) {
        for (TargetSpec target : targets) jdbc.update("INSERT INTO " + table + "(" + foreignKey
                + ",nutrient_id,unit_id,day_type,target_amount) VALUES (?,?,?,?,?)",
                parentId, target.nutrientId(), target.unitId(), target.dayType(), target.targetAmount());
    }

    private List<TargetSpec> targetSpecs(String table, String foreignKey, UUID parentId) {
        return jdbc.query("SELECT nutrient_id,unit_id,day_type,target_amount FROM " + table
                + " WHERE " + foreignKey + "=? ORDER BY nutrient_id,day_type",
                (rs, n) -> new TargetSpec(rs.getInt(1), rs.getInt(2), rs.getString(3),
                        rs.getBigDecimal(4)), parentId);
    }

    private List<TargetSpec> proposalTargets(UUID id) {
        return targetSpecs("fitness.nutrition_proposal_targets", "nutrition_proposal_id", id);
    }

    private List<Proposal> proposalsForStudent(UUID student) {
        return jdbc.query("""
                SELECT * FROM fitness.nutrition_proposals
                WHERE student_id=? AND proposal_kind IS NOT NULL
                ORDER BY created_at DESC,id DESC LIMIT 100
                """, (rs, n) -> presentProposal(proposalRow(rs)), student);
    }

    private ProposalRow proposalRow(UUID id) {
        return jdbc.query("SELECT * FROM fitness.nutrition_proposals WHERE id=? AND proposal_kind IS NOT NULL",
                (rs, n) -> proposalRow(rs), id).stream().findFirst().orElse(null);
    }

    private ProposalRow proposalRowForUpdate(UUID id) {
        return jdbc.query("SELECT * FROM fitness.nutrition_proposals WHERE id=? AND proposal_kind IS NOT NULL FOR UPDATE",
                (rs, n) -> proposalRow(rs), id).stream().findFirst()
                .orElseThrow(() -> fail("NUTRITION_PROPOSAL_NOT_FOUND", 404));
    }

    private Proposal presentProposal(ProposalRow row) {
        return new Proposal(row.id(), row.student(), row.trainer(), row.kind(), row.status(),
                row.version(), row.goalId(), row.baseVersionId(), row.baseGoalVersion(),
                row.title(), row.goalTypeId(), row.timezone(), row.effectiveFrom(), row.reason(),
                proposalTargets(row.id()), row.createdAt(), row.decidedAt(), row.decidedGoalId(),
                row.decidedGoalVersionId());
    }

    private com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision
    requireTrainer(UUID trainer, UUID student, DataAccessLevel level) {
        var decision = coaching.evaluateCurrent(new AuthorityRequest(trainer, student,
                DataScope.NUTRITION_GOAL, level));
        if (!decision.allowed()) throw fail("NUTRITION_PROPOSAL_NOT_FOUND", 404);
        return decision;
    }

    private void statusHistory(UUID goalId, String before, String after, UUID actor, String reason) {
        jdbc.update("""
                INSERT INTO fitness.nutrition_goal_status_history
                  (nutrition_goal_id,from_status,to_status,changed_by,reason,changed_at)
                VALUES (?,?::fitness.lifecycle_status,?::fitness.lifecycle_status,?,?,clock_timestamp())
                """, goalId, before, after, actor, reason);
    }

    private static GoalRow goalRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GoalRow((UUID) rs.getObject("id"), (UUID) rs.getObject("student_id"),
                rs.getString("title"), rs.getString("status"), rs.getLong("aggregate_version"),
                rs.getString("calendar_timezone"), rs.getBoolean("strategy_managed"));
    }

    private static ProposalRow proposalRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new ProposalRow((UUID) rs.getObject("id"), (UUID) rs.getObject("student_id"),
                (UUID) rs.getObject("created_by"), (UUID) rs.getObject("coaching_period_id"),
                rs.getString("proposal_kind"), rs.getString("status"), rs.getLong("proposal_version"),
                (UUID) rs.getObject("nutrition_goal_id"),
                (UUID) rs.getObject("base_nutrition_goal_version_id"),
                (Long) rs.getObject("base_goal_aggregate_version"), rs.getString("proposed_title"),
                rs.getInt("proposed_goal_type_id"), rs.getString("calendar_timezone"),
                rs.getObject("proposed_effective_from", LocalDate.class), rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(), decided == null ? null : decided.toInstant(),
                (UUID) rs.getObject("decided_goal_id"), (UUID) rs.getObject("decided_goal_version_id"));
    }

    private static NutritionFailure fail(String code, int status) { return new NutritionFailure(code, status); }
}
