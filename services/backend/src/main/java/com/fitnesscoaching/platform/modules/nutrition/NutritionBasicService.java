package com.fitnesscoaching.platform.modules.nutrition;

import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** First-flow, Student-owned basic nutrition. SQL remains within the Nutrition-owned tables. */
@Service
public class NutritionBasicService {
    private static final List<String> NUTRIENTS = List.of("B07_ENERGY_KCAL", "B07_PROTEIN_G",
            "B07_CARBOHYDRATE_G", "B07_FAT_G");
    private final JdbcTemplate jdbc;
    private final UserAccountStatusQuery accounts;
    private final StudentCapabilityQuery students;
    private final CoachingAuthorityQuery coaching;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final NutritionTargetDayService targetDays;

    public NutritionBasicService(JdbcTemplate jdbc, UserAccountStatusQuery accounts,
                                 StudentCapabilityQuery students, CoachingAuthorityQuery coaching,
                                 AuditService audit, ObjectMapper mapper,
                                 NutritionTargetDayService targetDays) {
        this.jdbc = jdbc; this.accounts = accounts; this.students = students;
        this.coaching = coaching; this.audit = audit; this.mapper = mapper;
        this.targetDays = targetDays;
    }

    public record FoodInput(String name, String servingLabel, BigDecimal energyKcal,
                            BigDecimal proteinG, BigDecimal carbohydrateG, BigDecimal fatG,
                            Long expectedVersion, String commandKey) {}
    public record Food(UUID id, UUID studentId, String name, String servingLabel, String source,
                       String quantificationStatus,
                       BigDecimal energyKcal, BigDecimal proteinG, BigDecimal carbohydrateG,
                       BigDecimal fatG, long version) {}
    public record ItemInput(String timezone, Instant consumedAt, String mealType, String description,
                            UUID privateFoodId, BigDecimal servings, String commandKey) {}
    public record CorrectionInput(long expectedVersion, String description, UUID privateFoodId,
                                  BigDecimal servings, boolean remove, String commandKey) {}
    public record ConfirmInput(String timezone, long expectedDayVersion, boolean noIntakeConfirmed,
                               String commandKey) {}
    public record Nutrient(String code, BigDecimal total, BigDecimal knownSubtotal,
                           int knownItems, int unknownItems) {}
    public record Item(UUID id, UUID mealId, String kind, String name, UUID privateFoodId,
                       BigDecimal servings, String servingLabel, Instant consumedAt, long version,
                       boolean removed, List<Nutrient> nutrients) {}
    public record Day(UUID studentId, LocalDate date, String timezone, String completeness,
                      long version, boolean noIntakeConfirmed, List<Nutrient> nutrients,
                      List<Item> items) {}
    public record Correction(UUID id, long fromVersion, long toVersion, String beforeJson,
                             String afterJson, Instant correctedAt) {}

    @Transactional
    public Food createFood(UUID actor, FoodInput input) {
        requireStudent(actor);
        validateFood(input, false);
        lockStudent(actor);
        return receipt(actor, input.commandKey(), "CREATE_FOOD", input, Food.class, () -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.student_private_foods(id,student_id,name,serving_label,
                      energy_kcal_per_serving,protein_g_per_serving,carbohydrate_g_per_serving,fat_g_per_serving)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, id, actor, input.name().trim(), input.servingLabel().trim(), input.energyKcal(),
                    input.proteinG(), input.carbohydrateG(), input.fatG());
            audit(actor, "NUTRITION_PRIVATE_FOOD_CREATED", "STUDENT_PRIVATE_FOOD", id);
            return findFood(actor, id);
        });
    }

    @Transactional
    public Food updateFood(UUID actor, UUID id, FoodInput input) {
        requireStudent(actor);
        validateFood(input, true);
        lockStudent(actor);
        return receipt(actor, input.commandKey(), "UPDATE_FOOD", List.of(id, input), Food.class, () -> {
            Food old = findFood(actor, id);
            if (old.version() != input.expectedVersion()) throw fail("NUTRITION_VERSION_STALE", 409);
            int updated = jdbc.update("""
                    UPDATE fitness.student_private_foods SET name=?,serving_label=?,
                      energy_kcal_per_serving=?,protein_g_per_serving=?,carbohydrate_g_per_serving=?,
                      fat_g_per_serving=?,version=version+1,updated_at=clock_timestamp()
                    WHERE id=? AND student_id=? AND version=? AND archived_at IS NULL
                    """, input.name().trim(), input.servingLabel().trim(), input.energyKcal(), input.proteinG(),
                    input.carbohydrateG(), input.fatG(), id, actor, input.expectedVersion());
            if (updated != 1) throw fail("NUTRITION_VERSION_STALE", 409);
            audit(actor, "NUTRITION_PRIVATE_FOOD_UPDATED", "STUDENT_PRIVATE_FOOD", id);
            return findFood(actor, id);
        });
    }

    @Transactional(readOnly = true)
    public List<Food> foods(UUID actor) {
        requireStudent(actor);
        return jdbc.query("""
                SELECT * FROM fitness.student_private_foods
                WHERE student_id=? AND archived_at IS NULL ORDER BY created_at DESC,id DESC LIMIT 100
                """, (rs, n) -> food(rs), actor);
    }

    @Transactional
    public Day addItem(UUID actor, LocalDate day, ItemInput input) {
        requireStudent(actor);
        if (input == null || input.consumedAt() == null || input.commandKey() == null
                || input.mealType() == null || !List.of("BREAKFAST","LUNCH","DINNER","SNACK","OTHER")
                        .contains(input.mealType())) throw fail("VALIDATION_FAILED", 400);
        ZoneId zone = zone(input.timezone());
        if (!input.consumedAt().atZone(zone).toLocalDate().equals(day)) throw fail("VALIDATION_FAILED", 400);
        checkItemShape(input.privateFoodId(), input.description(), input.servings());
        lockStudent(actor);
        return receipt(actor, input.commandKey(), "ADD_ITEM", List.of(day, input), Day.class, () -> {
            ensureDay(actor, day, input.timezone());
            UUID mealId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.meals(id,student_id,meal_type,consumed_at,title,created_by,log_date,timezone)
                    VALUES (?,?,?::fitness.meal_type,?,NULL,?,?,?)
                    """, mealId, actor, input.mealType(), java.sql.Timestamp.from(input.consumedAt()),
                    actor, day, input.timezone());
            UUID itemId = UUID.randomUUID();
            Food food = input.privateFoodId() == null ? null : requireLoggableFood(actor, input.privateFoodId());
            targetDays.freezeInCurrentTransaction(actor, day, input.timezone(), "FIRST_LOG");
            insertItem(actor, itemId, mealId, input.description(), input.servings(), food);
            recalculate(actor, day, false);
            audit(actor, "NUTRITION_ITEM_LOGGED", "FOOD_LOG_ITEM", itemId);
            return readMine(actor, day, input.timezone());
        });
    }

    @Transactional
    public Day correctItem(UUID actor, UUID itemId, CorrectionInput input) {
        requireStudent(actor);
        if (input == null || input.commandKey() == null || input.commandKey().isBlank()
                || input.expectedVersion() < 0) throw fail("VALIDATION_FAILED", 400);
        if (!input.remove()) checkItemShape(input.privateFoodId(), input.description(), input.servings());
        lockStudent(actor);
        return receipt(actor, input.commandKey(), "CORRECT_ITEM", List.of(itemId, input), Day.class, () -> {
            var prior = itemOwner(actor, itemId);
            if (prior.day() == null || prior.timezone() == null)
                throw fail("NUTRITION_LEGACY_SOURCE_UNKNOWN", 409);
            if (prior.version() != input.expectedVersion()) throw fail("NUTRITION_VERSION_STALE", 409);
            if (prior.removed()) throw fail("NUTRITION_ITEM_NOT_FOUND", 404);
            String before = json(new ItemCorrectionSnapshot(prior, itemNutrients(itemId)));
            Food food = input.remove() || input.privateFoodId() == null ? null : requireLoggableFood(actor, input.privateFoodId());
            String kind = input.remove() ? prior.kind() : food == null ? "DESCRIPTION_ONLY" : "PRIVATE_SERVING";
            String name = input.remove() ? prior.name() : food == null ? input.description().trim() : food.name();
            BigDecimal servings = input.remove() ? prior.servings() : input.servings();
            int updated = jdbc.update("""
                    UPDATE fitness.food_log_items SET entry_kind=?,food_name_snapshot=?,private_food_id=?,
                      private_food_version=?,serving_label_snapshot=?,quantity=?,unit_id=?,
                      item_version=item_version+1,deleted_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,
                      updated_at=clock_timestamp()
                    WHERE id=? AND item_version=? AND deleted_at IS NULL
                    """, kind, name, food == null ? null : food.id(), food == null ? null : food.version(),
                    food == null ? null : food.servingLabel(), servings,
                    food == null ? null : unit("B07_SERVING"), input.remove(), itemId, input.expectedVersion());
            if (updated != 1) throw fail("NUTRITION_VERSION_STALE", 409);
            // The correction record retains the old facts before replacing the current projection.
            jdbc.update("DELETE FROM fitness.food_log_item_nutrients WHERE food_log_item_id=?", itemId);
            if (!input.remove() && food != null) snapshotNutrients(itemId, food, servings);
            var after = itemOwner(actor, itemId);
            UUID correctionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.nutrition_item_corrections(id,food_log_item_id,actor_id,from_version,
                      to_version,before_json,after_json)
                    VALUES (?,?,?,?,?,?::jsonb,?::jsonb)
                    """, correctionId, itemId, actor, prior.version(), after.version(), before,
                    json(new ItemCorrectionSnapshot(after, itemNutrients(itemId))));
            recalculate(actor, prior.day(), false);
            audit(actor, input.remove() ? "NUTRITION_ITEM_REMOVED" : "NUTRITION_ITEM_CORRECTED",
                    "FOOD_LOG_ITEM", itemId);
            return readMine(actor, prior.day(), prior.timezone());
        });
    }

    @Transactional
    public Day confirmDay(UUID actor, LocalDate day, ConfirmInput input) {
        requireStudent(actor);
        if (input == null || input.expectedDayVersion() < 0) throw fail("VALIDATION_FAILED", 400);
        zone(input.timezone());
        lockStudent(actor);
        return receipt(actor, input.commandKey(), "CONFIRM_DAY", List.of(day, input), Day.class, () -> {
            long version = ensureDay(actor, day, input.timezone());
            if (version != input.expectedDayVersion()) throw fail("NUTRITION_VERSION_STALE", 409);
            int count = itemCount(actor, day);
            if ((count == 0) != input.noIntakeConfirmed()) throw fail("NUTRITION_DAY_CONFIRM_INVALID", 409);
            targetDays.freezeInCurrentTransaction(actor, day, input.timezone(), "DAY_CONFIRM");
            recalculate(actor, day, true);
            jdbc.update("""
                    UPDATE fitness.daily_nutrition_log_status SET completeness='COMPLETE',
                    no_intake_confirmed=?,confirmed_at=clock_timestamp() WHERE student_id=? AND log_date=?
                    """, input.noIntakeConfirmed(), actor, day);
            audit(actor, "NUTRITION_DAY_CONFIRMED", "NUTRITION_DAY", actor,
                    java.util.Map.of("date", day.toString(), "timezone", input.timezone(),
                            "noIntakeConfirmed", input.noIntakeConfirmed()));
            return readMine(actor, day, input.timezone());
        });
    }

    @Transactional(readOnly = true)
    public Day readMine(UUID actor, LocalDate day, String timezone) {
        requireStudent(actor);
        zone(timezone);
        return readDay(actor, day, timezone);
    }

    @Transactional
    public Day readForTrainer(UUID actor, UUID student, LocalDate day, String timezone) {
        zone(timezone);
        var decision = coaching.evaluateCurrent(new AuthorityRequest(actor, student,
                DataScope.NUTRITION_LOGS, DataAccessLevel.VIEW));
        if (!decision.allowed()) throw fail("NUTRITION_DAY_NOT_FOUND", 404);
        Instant from = day.atStartOfDay(zone(timezone)).toInstant();
        Instant until = day.plusDays(1).atStartOfDay(zone(timezone)).toInstant();
        if (decision.historyFrom() == null || decision.historyFrom().isAfter(from)
                || decision.historyUntil() != null && decision.historyUntil().isBefore(until))
            throw fail("NUTRITION_DAY_NOT_FOUND", 404);
        return readDay(student, day, timezone);
    }

    @Transactional(readOnly = true)
    public List<Correction> history(UUID actor, UUID itemId) {
        requireStudent(actor);
        itemOwner(actor, itemId);
        return jdbc.query("""
                SELECT id,from_version,to_version,before_json::text,after_json::text,corrected_at
                FROM fitness.nutrition_item_corrections WHERE food_log_item_id=? ORDER BY to_version
                """, (rs, n) -> new Correction((UUID) rs.getObject(1), rs.getLong(2), rs.getLong(3),
                rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()), itemId);
    }

    private Day readDay(UUID student, LocalDate day, String timezone) {
        List<DayState> states = jdbc.query("""
                SELECT timezone,completeness,day_version,no_intake_confirmed
                FROM fitness.daily_nutrition_log_status WHERE student_id=? AND log_date=?
                """, (rs, n) -> new DayState(rs.getString(1), rs.getString(2), rs.getLong(3),
                rs.getBoolean(4)), student, day);
        if (!states.isEmpty() && states.getFirst().timezone() != null
                && !timezone.equals(states.getFirst().timezone()))
            throw fail("NUTRITION_TIMEZONE_CONFLICT", 409);
        var state = states.isEmpty() ? new DayState(timezone, "NOT_LOGGED", 0, false) : states.getFirst();
        Instant start = day.atStartOfDay(zone(timezone)).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(zone(timezone)).toInstant();
        List<Item> items = jdbc.query("""
                SELECT i.id,i.meal_id,i.entry_kind,i.food_name_snapshot,i.private_food_id,i.quantity,
                  i.serving_label_snapshot,m.consumed_at,i.item_version,i.deleted_at
                FROM fitness.meals m JOIN fitness.food_log_items i ON i.meal_id=m.id
                WHERE m.student_id=? AND (m.log_date=? OR (m.log_date IS NULL
                    AND m.consumed_at>=? AND m.consumed_at<?))
                  AND m.deleted_at IS NULL AND i.deleted_at IS NULL
                ORDER BY m.consumed_at,i.id
                """, (rs, n) -> new Item((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getString(3),
                rs.getString(4), (UUID) rs.getObject(5), rs.getBigDecimal(6), rs.getString(7),
                rs.getTimestamp(8).toInstant(), rs.getLong(9), rs.getTimestamp(10) != null,
                itemNutrients((UUID) rs.getObject(1))), student, day,
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(end));
        List<Nutrient> nutrients = jdbc.query("""
                SELECT n.code,s.consumed_amount,s.known_subtotal,
                  (SELECT count(*) FROM fitness.food_log_items i JOIN fitness.meals m ON m.id=i.meal_id
                     WHERE m.student_id=? AND (m.log_date=? OR (m.log_date IS NULL
                       AND m.consumed_at>=? AND m.consumed_at<?)) AND i.deleted_at IS NULL)-s.unknown_item_count,
                  s.unknown_item_count
                FROM fitness.daily_nutrition_summaries s JOIN fitness.nutrients n ON n.id=s.nutrient_id
                WHERE s.student_id=? AND s.summary_date=? AND n.code LIKE 'B07_%' ORDER BY n.id
                """, (rs, n) -> new Nutrient(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                rs.getInt(4), rs.getInt(5)), student, day,
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(end), student, day);
        if (nutrients.isEmpty()) nutrients = NUTRIENTS.stream()
                .map(code -> new Nutrient(code, null, null, 0, items.size())).toList();
        return new Day(student, day, state.timezone(), state.completeness(), state.version(),
                state.noIntakeConfirmed(), nutrients, items);
    }

    private List<Nutrient> itemNutrients(UUID itemId) {
        return jdbc.query("""
                SELECT n.code,fn.amount FROM fitness.food_log_item_nutrients fn
                JOIN fitness.nutrients n ON n.id=fn.nutrient_id WHERE fn.food_log_item_id=? ORDER BY n.id
                """, (rs, n) -> new Nutrient(rs.getString(1), rs.getBigDecimal(2),
                rs.getBigDecimal(2), 1, 0), itemId);
    }

    private void insertItem(UUID actor, UUID itemId, UUID mealId, String description,
                            BigDecimal servings, Food food) {
        jdbc.update("""
                INSERT INTO fitness.food_log_items(id,meal_id,food_name_snapshot,quantity,unit_id,
                  entry_source,confirmed_by_user,confirmed_at,created_at,updated_at,entry_kind,
                  private_food_id,private_food_version,serving_label_snapshot)
                VALUES (?,?,?,?,?,'MANUAL',true,clock_timestamp(),clock_timestamp(),clock_timestamp(),?,?,?,?)
                """, itemId, mealId, food == null ? description.trim() : food.name(), servings,
                food == null ? null : unit("B07_SERVING"), food == null ? "DESCRIPTION_ONLY" : "PRIVATE_SERVING",
                food == null ? null : food.id(), food == null ? null : food.version(),
                food == null ? null : food.servingLabel());
        if (food != null) snapshotNutrients(itemId, food, servings);
    }

    private void snapshotNutrients(UUID itemId, Food food, BigDecimal servings) {
        List<BigDecimal> values = List.of(food.energyKcal() == null ? BigDecimal.valueOf(-1) : food.energyKcal(),
                food.proteinG() == null ? BigDecimal.valueOf(-1) : food.proteinG(),
                food.carbohydrateG() == null ? BigDecimal.valueOf(-1) : food.carbohydrateG(),
                food.fatG() == null ? BigDecimal.valueOf(-1) : food.fatG());
        for (int i = 0; i < NUTRIENTS.size(); i++) {
            if (values.get(i).signum() < 0) continue;
            BigDecimal amount = storedAmount(values.get(i).multiply(servings));
            jdbc.update("""
                    INSERT INTO fitness.food_log_item_nutrients(food_log_item_id,nutrient_id,amount,unit_id,calculation_source)
                    SELECT ?,n.id,?,n.default_unit_id,'USER_OVERRIDE' FROM fitness.nutrients n WHERE n.code=?
                    """, itemId, amount, NUTRIENTS.get(i));
        }
    }

    private void recalculate(UUID student, LocalDate day, boolean completing) {
        String timezone = jdbc.queryForObject("SELECT timezone FROM fitness.daily_nutrition_log_status WHERE student_id=? AND log_date=?",
                String.class, student, day);
        Instant start = day.atStartOfDay(zone(timezone)).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(zone(timezone)).toInstant();
        int count = itemCount(student, day);
        for (String code : NUTRIENTS) {
            var values = jdbc.query("""
                    SELECT fn.amount FROM fitness.meals m JOIN fitness.food_log_items i ON i.meal_id=m.id
                    LEFT JOIN fitness.food_log_item_nutrients fn ON fn.food_log_item_id=i.id
                      AND fn.nutrient_id=(SELECT id FROM fitness.nutrients WHERE code=?)
                    WHERE m.student_id=? AND (m.log_date=? OR (m.log_date IS NULL
                      AND m.consumed_at>=? AND m.consumed_at<?))
                      AND m.deleted_at IS NULL AND i.deleted_at IS NULL
                    """, (rs, n) -> rs.getBigDecimal(1), code, student, day,
                    java.sql.Timestamp.from(start), java.sql.Timestamp.from(end));
            int unknown = (int) values.stream().filter(value -> value == null).count();
            boolean hasKnown = values.stream().anyMatch(value -> value != null);
            BigDecimal subtotal = hasKnown || count == 0 && completing
                    ? storedAmount(values.stream().filter(value -> value != null).reduce(BigDecimal.ZERO,
                        BigDecimal::add)) : null;
            if (count == 0 && !completing) {
                jdbc.update("""
                        DELETE FROM fitness.daily_nutrition_summaries WHERE student_id=? AND summary_date=?
                          AND nutrient_id=(SELECT id FROM fitness.nutrients WHERE code=?)
                        """, student, day, code);
            } else {
                BigDecimal total = unknown == 0 ? subtotal : null;
                jdbc.update("""
                        INSERT INTO fitness.daily_nutrition_summaries(student_id,summary_date,nutrient_id,
                          consumed_amount,known_subtotal,unknown_item_count,unit_id,calculated_at)
                        SELECT ?,?,n.id,?,?,?,n.default_unit_id,clock_timestamp()
                        FROM fitness.nutrients n WHERE n.code=?
                        ON CONFLICT(student_id,summary_date,nutrient_id) DO UPDATE
                        SET consumed_amount=EXCLUDED.consumed_amount,known_subtotal=EXCLUDED.known_subtotal,
                          unknown_item_count=EXCLUDED.unknown_item_count,calculated_at=EXCLUDED.calculated_at
                        """, student, day, total, subtotal, unknown, code);
            }
        }
        jdbc.update("""
                UPDATE fitness.daily_nutrition_log_status SET completeness=?,no_intake_confirmed=false,
                  confirmed_at=NULL,day_version=day_version+1,updated_at=clock_timestamp()
                WHERE student_id=? AND log_date=?
                """, completing ? "COMPLETE" : count == 0 ? "NOT_LOGGED" : "PARTIAL", student, day);
    }

    private long ensureDay(UUID actor, LocalDate day, String timezone) {
        jdbc.update("""
                INSERT INTO fitness.daily_nutrition_log_status(student_id,log_date,completeness,timezone)
                VALUES (?,?,'NOT_LOGGED',?) ON CONFLICT(student_id,log_date) DO NOTHING
                """, actor, day, timezone);
        var state = jdbc.queryForObject("""
                SELECT timezone,day_version FROM fitness.daily_nutrition_log_status
                WHERE student_id=? AND log_date=? FOR UPDATE
                """, (rs, n) -> new DayState(rs.getString(1), null, rs.getLong(2), false), actor, day);
        if (state == null || !timezone.equals(state.timezone())) throw fail("NUTRITION_TIMEZONE_CONFLICT", 409);
        return state.version();
    }

    private int itemCount(UUID student, LocalDate day) {
        String timezone = jdbc.queryForObject("SELECT timezone FROM fitness.daily_nutrition_log_status WHERE student_id=? AND log_date=?",
                String.class, student, day);
        Instant start = day.atStartOfDay(zone(timezone)).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(zone(timezone)).toInstant();
        Integer value = jdbc.queryForObject("""
                SELECT count(*) FROM fitness.food_log_items i JOIN fitness.meals m ON m.id=i.meal_id
                WHERE m.student_id=? AND (m.log_date=? OR (m.log_date IS NULL
                  AND m.consumed_at>=? AND m.consumed_at<?))
                  AND m.deleted_at IS NULL AND i.deleted_at IS NULL
                """, Integer.class, student, day,
                java.sql.Timestamp.from(start), java.sql.Timestamp.from(end));
        return value == null ? 0 : value;
    }

    private ItemOwner itemOwner(UUID actor, UUID itemId) {
        var rows = jdbc.query("""
                SELECT m.log_date,m.timezone,i.entry_kind,i.food_name_snapshot,i.quantity,i.item_version,i.deleted_at
                FROM fitness.food_log_items i JOIN fitness.meals m ON m.id=i.meal_id
                WHERE i.id=? AND m.student_id=?
                """, (rs, n) -> new ItemOwner(rs.getObject(1, LocalDate.class), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getBigDecimal(5), rs.getLong(6),
                rs.getTimestamp(7) != null), itemId, actor);
        if (rows.isEmpty()) throw fail("NUTRITION_ITEM_NOT_FOUND", 404);
        return rows.getFirst();
    }

    private Food findFood(UUID actor, UUID id) {
        var rows = jdbc.query("""
                SELECT * FROM fitness.student_private_foods WHERE id=? AND student_id=? AND archived_at IS NULL
                """, (rs, n) -> food(rs), id, actor);
        if (rows.isEmpty()) throw fail("NUTRITION_FOOD_NOT_FOUND", 404);
        return rows.getFirst();
    }

    private Food requireLoggableFood(UUID actor, UUID id) {
        Food food = findFood(actor, id);
        if (!"LOGGABLE".equals(food.quantificationStatus())) throw fail("NUTRITION_FOOD_DRAFT", 409);
        return food;
    }

    private static Food food(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Food((UUID) rs.getObject("id"), (UUID) rs.getObject("student_id"), rs.getString("name"),
                rs.getString("serving_label"), rs.getString("source"), rs.getString("quantification_status"), rs.getBigDecimal("energy_kcal_per_serving"),
                rs.getBigDecimal("protein_g_per_serving"), rs.getBigDecimal("carbohydrate_g_per_serving"),
                rs.getBigDecimal("fat_g_per_serving"), rs.getLong("version"));
    }

    private int unit(String code) {
        Integer id = jdbc.queryForObject("SELECT id FROM fitness.measurement_units WHERE code=?", Integer.class, code);
        if (id == null) throw new IllegalStateException("Missing B07 unit catalog");
        return id;
    }

    private void requireStudent(UUID actor) {
        if (actor == null || accounts.getAccountStatus(actor).orElse(null) != AccountStatus.ACTIVE
                || !students.hasProfile(actor)) throw fail("NUTRITION_ACCESS_DENIED", 403);
    }

    private void lockStudent(UUID student) {
        // Nutrition-owned transaction advisory lock; no direct read of Student persistence.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 707))",
                rs -> { while (rs.next()) { /* hold until transaction end */ } }, student.toString());
    }

    private static void checkItemShape(UUID foodId, String description, BigDecimal servings) {
        if (foodId == null) {
            if (description == null || description.isBlank() || description.length() > 200 || servings != null)
                throw fail("VALIDATION_FAILED", 400);
        } else if (servings == null || servings.signum() <= 0 || servings.scale() > 6
                || servings.precision() > 18 || description != null && !description.isBlank())
            throw fail("VALIDATION_FAILED", 400);
        if (servings != null) storedAmount(servings);
    }

    private static BigDecimal storedAmount(BigDecimal value) {
        BigDecimal rounded = value.setScale(6, RoundingMode.HALF_UP);
        if (rounded.signum() < 0 || rounded.precision() - rounded.scale() > 12)
            throw fail("VALIDATION_FAILED", 400);
        return rounded;
    }

    private static void validateFood(FoodInput input, boolean update) {
        if (input == null || input.name() == null || input.name().isBlank() || input.name().length() > 200
                || input.servingLabel() == null || input.servingLabel().isBlank() || input.servingLabel().length() > 80
                || input.commandKey() == null || input.commandKey().isBlank() || input.commandKey().length() > 120
                || update && (input.expectedVersion() == null || input.expectedVersion() < 0))
            throw fail("VALIDATION_FAILED", 400);
        for (BigDecimal value : new BigDecimal[]{input.energyKcal(), input.proteinG(),
                input.carbohydrateG(), input.fatG()})
            if (value != null) {
                if (value.signum() < 0 || value.scale() > 6 || value.precision() > 18)
                    throw fail("VALIDATION_FAILED", 400);
                storedAmount(value);
            }
    }

    private static ZoneId zone(String timezone) {
        if (timezone == null || timezone.length() > 64 || !ZoneId.getAvailableZoneIds().contains(timezone))
            throw fail("VALIDATION_FAILED", 400);
        return ZoneId.of(timezone);
    }

    private <T> T receipt(UUID actor, String key, String command, Object payload, Class<T> type,
                          java.util.function.Supplier<T> work) {
        if (key == null || key.isBlank() || key.length() > 120) throw fail("VALIDATION_FAILED", 400);
        String hash = sha(json(payload));
        var rows = jdbc.query("""
                SELECT command_name,payload_hash,response_json::text FROM fitness.nutrition_command_receipts
                WHERE actor_id=? AND command_key=? FOR UPDATE
                """, (rs, n) -> new Receipt(rs.getString(1), rs.getString(2), rs.getString(3)), actor, key);
        if (!rows.isEmpty()) {
            var prior = rows.getFirst();
            if (!prior.command().equals(command) || !prior.hash().equals(hash))
                throw fail("NUTRITION_IDEMPOTENCY_CONFLICT", 409);
            try { return mapper.readValue(prior.response(), type); }
            catch (Exception invalid) { throw new IllegalStateException("Invalid nutrition receipt", invalid); }
        }
        T result = work.get();
        jdbc.update("""
                INSERT INTO fitness.nutrition_command_receipts(actor_id,command_key,command_name,payload_hash,response_json)
                VALUES (?,?,?,?,?::jsonb)
                """, actor, key, command, hash, json(result));
        return result;
    }

    private void audit(UUID actor, String action, String type, UUID target) {
        audit(actor, action, type, target, java.util.Map.of());
    }

    private void audit(UUID actor, String action, String type, UUID target, Object detail) {
        Instant at = jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
        audit.recordAudit(AuditRecord.builder().actorUserId(actor).actorRole("STUDENT")
                .action(action).targetType(type).targetId(target).beforeDataJson("{}")
                .afterDataJson(json(detail)).occurredAt(at).build());
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalStateException("Cannot serialize nutrition command", invalid); }
    }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static NutritionFailure fail(String code, int status) { return new NutritionFailure(code, status); }
    private record DayState(String timezone, String completeness, long version, boolean noIntakeConfirmed) {}
    private record ItemOwner(LocalDate day, String timezone, String kind, String name,
                             BigDecimal servings, long version, boolean removed) {}
    private record ItemCorrectionSnapshot(ItemOwner item, List<Nutrient> nutrientFacts) {}
    private record Receipt(String command, String hash, String response) {}
}
