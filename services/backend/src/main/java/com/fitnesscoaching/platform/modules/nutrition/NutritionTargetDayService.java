package com.fitnesscoaching.platform.modules.nutrition;

import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementUnitReferenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutTrainingDaySignalQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Target resolution is separate from actual Food Log calculations. */
@Service
public class NutritionTargetDayService {
    private final JdbcTemplate jdbc;
    private final NutritionStrategyCommands commands;
    private final WorkoutTrainingDaySignalQuery workouts;
    private final MeasurementUnitReferenceQuery units;
    private final CoachingAuthorityQuery coaching;

    public NutritionTargetDayService(JdbcTemplate jdbc, NutritionStrategyCommands commands,
                                     WorkoutTrainingDaySignalQuery workouts, MeasurementUnitReferenceQuery units,
                                     CoachingAuthorityQuery coaching) {
        this.jdbc = jdbc; this.commands = commands; this.workouts = workouts; this.units = units;
        this.coaching = coaching;
    }

    public record ResolvedTarget(int nutrientId, int unitId, BigDecimal targetAmount, String source,
                                 UUID sourceTargetId, UUID sourceOverrideId, Long overrideVersion,
                                 BigDecimal originalTargetAmount, Long correctionVersion) {
        public ResolvedTarget(int nutrientId, int unitId, BigDecimal targetAmount, String source,
                              UUID sourceTargetId, UUID sourceOverrideId, Long overrideVersion) {
            this(nutrientId, unitId, targetAmount, source, sourceTargetId, sourceOverrideId,
                    overrideVersion, null, null);
        }
    }
    public record TargetDay(LocalDate date, String timezone, boolean frozen, String dayType,
                            UUID goalVersionId, List<ResolvedTarget> targets) {}
    public record FrozenCorrectionInput(String timezone, int nutrientId, BigDecimal targetAmount,
                                        long expectedVersion, String reason, String commandKey) {}
    public record FrozenCorrection(UUID id, LocalDate date, int nutrientId, int unitId, long version,
                                   BigDecimal originalAmount, BigDecimal beforeAmount, BigDecimal afterAmount,
                                   String reason, UUID actorId, java.time.Instant correctedAt) {}
    public record DayTypeInput(String timezone, String dayType, String commandKey) {}
    public record OverrideInput(String timezone, UUID goalVersionId, int nutrientId, int unitId,
                                BigDecimal targetAmount, String reason, Long expectedOverrideVersion,
                                String commandKey) {}
    private record Version(UUID id, String timezone) {}

    @Transactional(readOnly = true)
    public TargetDay preview(UUID actor, LocalDate day, String timezone) {
        commands.requireStudent(actor);
        validZone(timezone);
        return resolve(actor, day, timezone);
    }

    @Transactional
    public TargetDay correctFrozen(UUID actor, LocalDate day, FrozenCorrectionInput input) {
        commands.requireStudent(actor);
        if (input == null || input.nutrientId() <= 0 || input.targetAmount() == null
                || input.targetAmount().signum() < 0 || input.targetAmount().scale() > 6
                || input.targetAmount().precision() > 18 || input.expectedVersion() < 0
                || input.reason() == null || input.reason().isBlank() || input.reason().length() > 1000)
            throw fail("VALIDATION_FAILED", 400);
        validZone(input.timezone());
        commands.lockStudent(actor);
        return commands.receipt(actor, input.commandKey(), "NUTRITION_FROZEN_TARGET_CORRECTION",
                List.of(day, input), TargetDay.class, () -> {
                    TargetDay frozen = resolve(actor, day, input.timezone());
                    if (!frozen.frozen()) throw fail("NUTRITION_TARGET_NOT_FROZEN", 409);
                    var original = jdbc.query("""
                            SELECT id,unit_id,target_amount FROM fitness.resolved_daily_nutrition_targets
                            WHERE student_id=? AND target_date=? AND nutrient_id=?
                            """, (rs, n) -> new OriginalTarget((UUID) rs.getObject(1), rs.getInt(2),
                            rs.getBigDecimal(3)), actor, day, input.nutrientId());
                    if (original.size() != 1) throw fail("NUTRITION_TARGET_NOT_FOUND", 404);
                    OriginalTarget fact = original.getFirst();
                    var previous = jdbc.query("""
                            SELECT version,after_amount FROM fitness.nutrition_frozen_target_corrections
                            WHERE student_id=? AND target_date=? AND nutrient_id=?
                            ORDER BY version DESC LIMIT 1
                            """, (rs, n) -> new PriorCorrection(rs.getLong(1), rs.getBigDecimal(2)),
                            actor, day, input.nutrientId());
                    long version = previous.isEmpty() ? 0 : previous.getFirst().version();
                    if (version != input.expectedVersion()) throw fail("NUTRITION_VERSION_STALE", 409);
                    BigDecimal before = previous.isEmpty() ? fact.amount() : previous.getFirst().amount();
                    UUID id = UUID.randomUUID();
                    jdbc.update("""
                            INSERT INTO fitness.nutrition_frozen_target_corrections
                              (id,student_id,target_date,nutrient_id,version,original_target_id,unit_id,
                               before_amount,after_amount,reason,actor_id)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?)
                            """, id, actor, day, input.nutrientId(), version + 1, fact.id(), fact.unitId(),
                            before, input.targetAmount(), input.reason().trim(), actor);
                    commands.audit(actor, "STUDENT", "NUTRITION_FROZEN_TARGET_CORRECTED",
                            "NUTRITION_TARGET_CORRECTION", id,
                            java.util.Map.of("date", day.toString(), "nutrientId", input.nutrientId(),
                                    "before", before, "after", input.targetAmount(),
                                    "reason", input.reason().trim()));
                    return resolve(actor, day, input.timezone());
                });
    }

    @Transactional(readOnly = true)
    public List<FrozenCorrection> correctionHistory(UUID actor, UUID student, LocalDate day) {
        if (actor.equals(student)) commands.requireStudent(actor);
        else if (!coaching.evaluateCurrent(new AuthorityRequest(actor, student,
                DataScope.NUTRITION_GOAL, DataAccessLevel.VIEW)).allowed())
            throw fail("NUTRITION_TARGET_NOT_FOUND", 404);
        return jdbc.query("""
                SELECT c.id,c.nutrient_id,c.unit_id,c.version,r.target_amount,
                       c.before_amount,c.after_amount,c.reason,c.actor_id,c.corrected_at
                FROM fitness.nutrition_frozen_target_corrections c
                JOIN fitness.resolved_daily_nutrition_targets r ON r.id=c.original_target_id
                WHERE c.student_id=? AND c.target_date=?
                ORDER BY c.nutrient_id,c.version
                """, (rs, n) -> new FrozenCorrection((UUID) rs.getObject(1), day, rs.getInt(2),
                rs.getInt(3), rs.getLong(4), rs.getBigDecimal(5), rs.getBigDecimal(6),
                rs.getBigDecimal(7), rs.getString(8), (UUID) rs.getObject(9),
                rs.getTimestamp(10).toInstant()), student, day);
    }

    @Transactional
    public TargetDay chooseDayType(UUID actor, LocalDate day, DayTypeInput input) {
        commands.requireStudent(actor);
        if (input == null || !List.of("REST_DAY", "DEFAULT").contains(input.dayType()))
            throw fail("VALIDATION_FAILED", 400);
        validZone(input.timezone());
        commands.lockStudent(actor);
        return commands.receipt(actor, input.commandKey(), "NUTRITION_DAY_TYPE", List.of(day, input),
                TargetDay.class, () -> {
                    requireUnfrozen(actor, day);
                    TargetDay current = resolve(actor, day, input.timezone());
                    if ("REST_DAY".equals(input.dayType())) {
                        jdbc.update("""
                                INSERT INTO fitness.nutrition_day_type_choices(student_id,target_date,calendar_timezone,day_type,chosen_by)
                                VALUES (?,?,?,'REST_DAY',?) ON CONFLICT (student_id,target_date) DO UPDATE
                                SET version=nutrition_day_type_choices.version+1,chosen_by=EXCLUDED.chosen_by,
                                    calendar_timezone=EXCLUDED.calendar_timezone,chosen_at=clock_timestamp()
                                """, actor, day, input.timezone(), actor);
                    } else {
                        jdbc.update("DELETE FROM fitness.nutrition_day_type_choices WHERE student_id=? AND target_date=?",
                                actor, day);
                    }
                    jdbc.update("""
                            INSERT INTO fitness.nutrition_day_type_history(student_id,target_date,calendar_timezone,before_type,after_type,actor_id)
                            VALUES (?,?,?,?,?,?)
                            """, actor, day, input.timezone(), current.dayType(), input.dayType(), actor);
                    commands.audit(actor, "STUDENT", "NUTRITION_DAY_TYPE_SELECTED", "NUTRITION_DAY", actor,
                            Map.of("date", day.toString(), "dayType", input.dayType()));
                    return resolve(actor, day, input.timezone());
                });
    }

    @Transactional
    public TargetDay upsertOverride(UUID actor, LocalDate day, OverrideInput input) {
        commands.requireStudent(actor);
        if (input == null || input.goalVersionId() == null || input.nutrientId() <= 0 || input.unitId() <= 0
                || input.targetAmount() == null || input.targetAmount().signum() < 0
                || input.targetAmount().scale() > 6 || input.targetAmount().precision() > 18
                || input.reason() == null || input.reason().isBlank() || input.reason().length() > 1000)
            throw fail("VALIDATION_FAILED", 400);
        validZone(input.timezone());
        commands.lockStudent(actor);
        return commands.receipt(actor, input.commandKey(), "NUTRITION_OVERRIDE", List.of(day, input),
                TargetDay.class, () -> {
                    requireUnfrozen(actor, day);
                    TargetDay prior = resolve(actor, day, input.timezone());
                    if (!input.goalVersionId().equals(prior.goalVersionId()))
                        throw fail("NUTRITION_TARGET_VERSION_STALE", 409);
                    validateUnit(input.nutrientId(), input.unitId());
                    var existing = jdbc.query("""
                            SELECT id,version,nutrition_goal_version_id,target_amount,unit_id,reason
                            FROM fitness.daily_nutrition_target_overrides
                            WHERE student_id=? AND target_date=? AND nutrient_id=? FOR UPDATE
                            """, (rs, n) -> new OverrideRow((UUID) rs.getObject(1), rs.getLong(2),
                            (UUID) rs.getObject(3), rs.getBigDecimal(4), rs.getInt(5), rs.getString(6)),
                            actor, day, input.nutrientId());
                    UUID id;
                    String before = null;
                    long nextVersion;
                    if (existing.isEmpty()) {
                        if (input.expectedOverrideVersion() != null) throw fail("NUTRITION_VERSION_STALE", 409);
                        id = UUID.randomUUID(); nextVersion = 0;
                        jdbc.update("""
                                INSERT INTO fitness.daily_nutrition_target_overrides
                                  (id,student_id,nutrition_goal_version_id,target_date,nutrient_id,target_amount,
                                   unit_id,reason,created_by) VALUES (?,?,?,?,?,?,?,?,?)
                                """, id, actor, input.goalVersionId(), day, input.nutrientId(),
                                input.targetAmount(), input.unitId(), input.reason().trim(), actor);
                    } else {
                        OverrideRow old = existing.getFirst();
                        if (!old.goalVersionId().equals(input.goalVersionId()))
                            throw fail("NUTRITION_TARGET_VERSION_STALE", 409);
                        if (input.expectedOverrideVersion() == null || old.version() != input.expectedOverrideVersion())
                            throw fail("NUTRITION_VERSION_STALE", 409);
                        id = old.id(); nextVersion = old.version() + 1;
                        before = commands.json(old);
                        jdbc.update("""
                                UPDATE fitness.daily_nutrition_target_overrides
                                SET target_amount=?,unit_id=?,reason=?,version=version+1,updated_at=clock_timestamp()
                                WHERE id=? AND version=?
                                """, input.targetAmount(), input.unitId(), input.reason().trim(), id,
                                old.version());
                    }
                    jdbc.update("""
                            INSERT INTO fitness.nutrition_override_history
                              (override_id,student_id,from_version,to_version,before_json,after_json,changed_by)
                            VALUES (?,?,?,?,?::jsonb,?::jsonb,?)
                            """, id, actor, existing.isEmpty() ? null : nextVersion - 1, nextVersion,
                            before, commands.json(input), actor);
                    commands.audit(actor, "STUDENT", "NUTRITION_TARGET_OVERRIDE_SAVED", "NUTRITION_OVERRIDE", id,
                            Map.of("date", day.toString(), "nutrientId", input.nutrientId()));
                    return resolve(actor, day, input.timezone());
                });
    }

    /** Called inside the Basic Nutrition write transaction after the Student day lock. */
    void freezeInCurrentTransaction(UUID actor, LocalDate day, String logTimezone, String trigger) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.nutrition_target_day_snapshots
                WHERE student_id=? AND target_date=?)
                """, Boolean.class, actor, day))) return;
        if (hasLegacyResolvedRows(actor, day)) return; // Preserve old facts; no invented timezone/provenance.
        String targetTimezone = jdbc.query("""
                SELECT v.calendar_timezone FROM fitness.nutrition_goals g
                JOIN fitness.nutrition_goal_versions v ON v.nutrition_goal_id=g.id
                WHERE g.student_id=? AND g.status='ACTIVE' AND g.strategy_managed
                  AND v.date_semantics='HALF_OPEN' AND v.effective_from<=?
                  AND (v.effective_until IS NULL OR v.effective_until>?)
                ORDER BY v.version_number DESC LIMIT 1
                """, (rs, n) -> rs.getString(1), actor, day, day).stream()
                .findFirst().orElse(logTimezone);
        TargetDay resolved = resolve(actor, day, targetTimezone);
        int inserted = jdbc.update("""
                INSERT INTO fitness.nutrition_target_day_snapshots
                  (student_id,target_date,nutrition_goal_version_id,calendar_timezone,resolved_day_type,
                   trigger_kind,frozen_by)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT (student_id,target_date) DO NOTHING
                """, actor, day, resolved.goalVersionId(), resolved.timezone(), resolved.dayType(), trigger, actor);
        if (inserted == 0) return;
        for (ResolvedTarget target : resolved.targets()) {
            jdbc.update("""
                    INSERT INTO fitness.resolved_daily_nutrition_targets
                      (student_id,target_date,nutrition_goal_version_id,nutrient_id,target_amount,unit_id,
                       day_type,source_override_id,source_target_id)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, actor, day, resolved.goalVersionId(), target.nutrientId(), target.targetAmount(),
                    target.unitId(), resolved.dayType(), target.sourceOverrideId(), target.sourceTargetId());
        }
        commands.audit(actor, "STUDENT", "NUTRITION_TARGET_DAY_FROZEN", "NUTRITION_DAY", actor,
                Map.of("date", day.toString(), "trigger", trigger));
    }

    private TargetDay resolve(UUID student, LocalDate day, String timezone) {
        var snapshots = jdbc.query("""
                SELECT nutrition_goal_version_id,calendar_timezone,resolved_day_type
                FROM fitness.nutrition_target_day_snapshots WHERE student_id=? AND target_date=?
                """, (rs, n) -> new Snapshot((UUID) rs.getObject(1), rs.getString(2), rs.getString(3)),
                student, day);
        if (!snapshots.isEmpty()) {
            Snapshot frozen = snapshots.getFirst();
            if (!frozen.timezone().equals(timezone)) throw fail("NUTRITION_TIMEZONE_CONFLICT", 409);
            List<ResolvedTarget> targets = jdbc.query("""
                    SELECT r.nutrient_id,r.unit_id,r.target_amount,r.source_target_id,
                           r.source_override_id,src.day_type
                    FROM fitness.resolved_daily_nutrition_targets r
                    LEFT JOIN fitness.daily_nutrition_targets src ON src.id=r.source_target_id
                    WHERE r.student_id=? AND r.target_date=?
                    ORDER BY r.nutrient_id
                    """, (rs, n) -> new ResolvedTarget(rs.getInt(1), rs.getInt(2), rs.getBigDecimal(3),
                    rs.getObject(5) != null ? "OVERRIDE" : "DEFAULT".equals(rs.getString(6))
                            ? "DEFAULT" : "DAY_TYPE",
                    (UUID) rs.getObject(4), (UUID) rs.getObject(5), null), student, day);
            var corrections = jdbc.query("""
                    SELECT DISTINCT ON (nutrient_id) nutrient_id,version,after_amount
                    FROM fitness.nutrition_frozen_target_corrections
                    WHERE student_id=? AND target_date=?
                    ORDER BY nutrient_id,version DESC
                    """, (rs, n) -> new CurrentCorrection(rs.getInt(1), rs.getLong(2), rs.getBigDecimal(3)),
                    student, day);
            Map<Integer, CurrentCorrection> current = new LinkedHashMap<>();
            for (CurrentCorrection correction : corrections) current.put(correction.nutrientId(), correction);
            List<ResolvedTarget> display = new ArrayList<>();
            for (ResolvedTarget target : targets) {
                CurrentCorrection corrected = current.get(target.nutrientId());
                display.add(new ResolvedTarget(target.nutrientId(), target.unitId(),
                        corrected == null ? target.targetAmount() : corrected.amount(),
                        corrected == null ? target.source() : "CORRECTED",
                        target.sourceTargetId(), target.sourceOverrideId(), target.overrideVersion(),
                        target.targetAmount(), corrected == null ? 0L : corrected.version()));
            }
            return new TargetDay(day, timezone, true, frozen.dayType(), frozen.versionId(), display);
        }
        if (hasLegacyResolvedRows(student, day))
            throw fail("NUTRITION_LEGACY_TARGET_UNRESOLVED", 409);
        Version version = jdbc.query("""
                SELECT v.id,v.calendar_timezone FROM fitness.nutrition_goals g
                JOIN fitness.nutrition_goal_versions v ON v.nutrition_goal_id=g.id
                WHERE g.student_id=? AND g.status='ACTIVE' AND g.strategy_managed
                  AND v.date_semantics='HALF_OPEN' AND v.effective_from<=?
                  AND (v.effective_until IS NULL OR v.effective_until>?)
                ORDER BY v.version_number DESC LIMIT 1
                """, (rs, n) -> new Version((UUID) rs.getObject(1), rs.getString(2)),
                student, day, day).stream().findFirst().orElse(null);
        if (version != null && !timezone.equals(version.timezone()))
            throw fail("NUTRITION_TIMEZONE_CONFLICT", 409);
        boolean rest = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.nutrition_day_type_choices
                WHERE student_id=? AND target_date=? AND calendar_timezone=?)
                """, Boolean.class, student, day, timezone));
        String dayType = rest ? "REST_DAY" : workouts.hasConfirmedOrPerformedWorkout(student, day, validZone(timezone))
                ? "TRAINING_DAY" : "DEFAULT";
        if (version == null) return new TargetDay(day, timezone, false, dayType, null, List.of());
        List<TargetCandidate> candidates = jdbc.query("""
                SELECT t.id,t.nutrient_id,t.unit_id,t.target_amount,t.day_type
                FROM fitness.daily_nutrition_targets t WHERE t.nutrition_goal_version_id=?
                  AND t.day_type IN (?,'DEFAULT') ORDER BY t.nutrient_id,t.day_type
                """, (rs, n) -> new TargetCandidate((UUID) rs.getObject(1), rs.getInt(2),
                rs.getInt(3), rs.getBigDecimal(4), rs.getString(5)), version.id(), dayType);
        Map<Integer, ResolvedTarget> selected = new LinkedHashMap<>();
        for (TargetCandidate candidate : candidates) {
            ResolvedTarget current = selected.get(candidate.nutrientId());
            if (current == null || candidate.dayType().equals(dayType))
                selected.put(candidate.nutrientId(), new ResolvedTarget(candidate.nutrientId(),
                        candidate.unitId(), candidate.amount(),
                        candidate.dayType().equals("DEFAULT") ? "DEFAULT" : "DAY_TYPE", candidate.id(), null, null));
        }
        var overrides = jdbc.query("""
                SELECT id,nutrient_id,unit_id,target_amount,version FROM fitness.daily_nutrition_target_overrides
                WHERE student_id=? AND target_date=? AND nutrition_goal_version_id=? ORDER BY nutrient_id
                """, (rs, n) -> new ResolvedTarget(rs.getInt(2), rs.getInt(3), rs.getBigDecimal(4),
                "OVERRIDE", null, (UUID) rs.getObject(1), rs.getLong(5)), student, day, version.id());
        for (ResolvedTarget override : overrides) selected.put(override.nutrientId(), override);
        return new TargetDay(day, timezone, false, dayType, version.id(), new ArrayList<>(selected.values()));
    }

    void validateUnit(int nutrientId, int unitId) {
        List<Short> defaults = jdbc.query("SELECT default_unit_id FROM fitness.nutrients WHERE id=?",
                (rs, n) -> rs.getShort(1), nutrientId);
        if (defaults.size() != 1 || unitId < Short.MIN_VALUE || unitId > Short.MAX_VALUE)
            throw fail("VALIDATION_FAILED", 400);
        var resolved = units.resolveAll(List.of(defaults.getFirst(), (short) unitId));
        var expected = resolved.get(defaults.getFirst());
        var supplied = resolved.get((short) unitId);
        if (expected == null || supplied == null || !expected.dimension().equals(supplied.dimension()))
            throw fail("VALIDATION_FAILED", 400);
    }

    static ZoneId validZone(String timezone) {
        if (timezone == null || timezone.length() > 64 || !ZoneId.getAvailableZoneIds().contains(timezone))
            throw fail("VALIDATION_FAILED", 400);
        return ZoneId.of(timezone);
    }

    private void requireUnfrozen(UUID student, LocalDate day) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.nutrition_target_day_snapshots
                WHERE student_id=? AND target_date=?)
                """, Boolean.class, student, day))) throw fail("NUTRITION_TARGET_DAY_FROZEN", 409);
    }

    private boolean hasLegacyResolvedRows(UUID student, LocalDate day) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.resolved_daily_nutrition_targets
                WHERE student_id=? AND target_date=?)
                """, Boolean.class, student, day));
    }

    private static NutritionFailure fail(String code, int status) { return new NutritionFailure(code, status); }
    private record Snapshot(UUID versionId, String timezone, String dayType) {}
    private record TargetCandidate(UUID id, int nutrientId, int unitId, BigDecimal amount, String dayType) {}
    private record OverrideRow(UUID id, long version, UUID goalVersionId, BigDecimal amount,
                               int unitId, String reason) {}
    private record OriginalTarget(UUID id, int unitId, BigDecimal amount) {}
    private record PriorCorrection(long version, BigDecimal amount) {}
    private record CurrentCorrection(int nutrientId, long version, BigDecimal amount) {}
}
