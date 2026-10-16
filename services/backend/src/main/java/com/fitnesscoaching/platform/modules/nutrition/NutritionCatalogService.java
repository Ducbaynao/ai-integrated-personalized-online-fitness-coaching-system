package com.fitnesscoaching.platform.modules.nutrition;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserPermissionQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Nutrition-owned curated USDA subset. No live USDA request is required at runtime. */
@Service
public class NutritionCatalogService {
    private final JdbcTemplate jdbc;
    private final NutritionStrategyCommands commands;
    private final UserAccountStatusQuery accounts;
    private final UserRoleQuery roles;
    private final UserPermissionQuery permissions;

    public NutritionCatalogService(JdbcTemplate jdbc, NutritionStrategyCommands commands,
                                   UserAccountStatusQuery accounts, UserRoleQuery roles,
                                   UserPermissionQuery permissions) {
        this.jdbc = jdbc; this.commands = commands; this.accounts = accounts;
        this.roles = roles; this.permissions = permissions;
    }

    public record PortionInput(String label, BigDecimal gramsPerServing, BigDecimal gramsPerMl,
                               String factorSource, String mlFactorSource) {}
    public record DraftInput(long fdcId, String sourceRelease, java.time.LocalDate sourceDate,
                             RevisionInput revision, String commandKey) {}
    public record RevisionInput(String name, BigDecimal energyKcalPer100g,
                                BigDecimal proteinGPer100g, BigDecimal carbohydrateGPer100g,
                                BigDecimal fatGPer100g, String sourceRecordVersion, String sourceUrl,
                                List<PortionInput> portions) {}
    public record Portion(UUID id, String label, BigDecimal gramsPerServing, BigDecimal gramsPerMl,
                          String factorSource, String mlFactorSource) {}
    public record Food(UUID id, long fdcId, String dataType, String sourceName,
                       String sourceRelease, java.time.LocalDate sourceDate, String status,
                       long aggregateVersion, UUID revisionId, int revisionNumber, String name,
                       BigDecimal energyKcalPer100g, BigDecimal proteinGPer100g,
                       BigDecimal carbohydrateGPer100g, BigDecimal fatGPer100g,
                       String sourceRecordVersion, String sourceUrl, List<Portion> portions) {}

    @Transactional
    public Food draft(UUID admin, DraftInput input) {
        requireAdmin(admin);
        if (input == null || input.fdcId() <= 0 || blank(input.sourceRelease())
                || input.sourceRelease().length() > 40
                || input.sourceDate() == null) throw fail("VALIDATION_FAILED", 400);
        validateRevision(input.fdcId(), input.revision());
        return commands.receipt(admin, input.commandKey(), "NUTRITION_CATALOG_DRAFT", input,
                Food.class, () -> {
                    UUID foodId = UUID.randomUUID();
                    int inserted = jdbc.update("""
                            INSERT INTO fitness.nutrition_catalog_foods
                              (id,fdc_id,data_type,source_release,source_date,created_by)
                            VALUES (? ,? ,'Foundation',?,?,?) ON CONFLICT (fdc_id) DO NOTHING
                            """, foodId, input.fdcId(), input.sourceRelease().trim(),
                            input.sourceDate(), admin);
                    if (inserted != 1) throw fail("NUTRITION_CATALOG_FDC_CONFLICT", 409);
                    insertRevision(admin, foodId, 1, input.revision());
                    commands.audit(admin, "ADMIN", "NUTRITION_CATALOG_DRAFT_CREATED",
                            "NUTRITION_CATALOG_FOOD", foodId, Map.of("fdcId", input.fdcId()));
                    return find(foodId);
                });
    }

    @Transactional
    public Food revise(UUID admin, UUID foodId, long expectedVersion, RevisionInput input,
                       String commandKey) {
        requireAdmin(admin);
        if (expectedVersion < 0 || input == null) throw fail("VALIDATION_FAILED", 400);
        return commands.receipt(admin, commandKey, "NUTRITION_CATALOG_REVISE",
                List.of(foodId, expectedVersion, input), Food.class, () -> {
                    Food old = lock(foodId);
                    if (old.aggregateVersion() != expectedVersion || "RETIRED".equals(old.status()))
                        throw fail("NUTRITION_CATALOG_VERSION_CONFLICT", 409);
                    validateRevision(old.fdcId(), input);
                    insertRevision(admin, foodId, old.revisionNumber() + 1, input);
                    jdbc.update("""
                            UPDATE fitness.nutrition_catalog_foods
                            SET aggregate_version=aggregate_version+1,updated_at=clock_timestamp()
                            WHERE id=?
                            """, foodId);
                    Food revised = find(foodId);
                    commands.audit(admin, "ADMIN", "NUTRITION_CATALOG_REVISED",
                            "NUTRITION_CATALOG_FOOD", foodId,
                            Map.of("fromRevisionId", old.revisionId(), "toRevisionId", revised.revisionId(),
                                    "fromRevision", old.revisionNumber(), "toRevision", revised.revisionNumber()));
                    return revised;
                });
    }

    @Transactional
    public Food transition(UUID admin, UUID foodId, long expectedVersion,
                           String action, String reason, String commandKey) {
        requireAdmin(admin);
        if (expectedVersion < 0 || blank(reason) || !List.of("PUBLISH", "RETIRE").contains(action))
            throw fail("VALIDATION_FAILED", 400);
        return commands.receipt(admin, commandKey, "NUTRITION_CATALOG_" + action,
                List.of(foodId, expectedVersion, action, reason), Food.class, () -> {
                    Food old = lock(foodId);
                    if (old.aggregateVersion() != expectedVersion
                            || "PUBLISH".equals(action) && !"DRAFT".equals(old.status())
                            || "RETIRE".equals(action) && !"PUBLISHED".equals(old.status()))
                        throw fail("NUTRITION_CATALOG_VERSION_CONFLICT", 409);
                    if ("PUBLISH".equals(action) && old.energyKcalPer100g() == null
                            && old.proteinGPer100g() == null && old.carbohydrateGPer100g() == null
                            && old.fatGPer100g() == null) throw fail("NUTRITION_CATALOG_UNQUANTIFIED", 409);
                    jdbc.update("""
                            UPDATE fitness.nutrition_catalog_foods SET status=?,
                                aggregate_version=aggregate_version+1,updated_at=clock_timestamp()
                            WHERE id=?
                            """, "PUBLISH".equals(action) ? "PUBLISHED" : "RETIRED", foodId);
                    commands.audit(admin, "ADMIN", "NUTRITION_CATALOG_" + action,
                            "NUTRITION_CATALOG_FOOD", foodId,
                            Map.of("from", old.status(), "reason", reason.trim()));
                    return find(foodId);
                });
    }

    @Transactional(readOnly = true)
    public List<Food> published() {
        return jdbc.query("""
                SELECT id FROM fitness.nutrition_catalog_foods WHERE status='PUBLISHED'
                ORDER BY id LIMIT 100
                """, (rs, n) -> find((UUID) rs.getObject(1)));
    }

    @Transactional(readOnly = true)
    public Food detail(UUID id) {
        Food food = find(id);
        if (!"PUBLISHED".equals(food.status())) throw fail("NUTRITION_CATALOG_NOT_FOUND", 404);
        return food;
    }

    /** Hold a row lock through the Student log transaction so revision/retirement cannot race the snapshot. */
    @Transactional
    public Food publishedForLogging(UUID id) {
        jdbc.query("SELECT id FROM fitness.nutrition_catalog_foods WHERE id=? FOR SHARE",
                rs -> { while (rs.next()) { /* held through transaction */ } }, id);
        return detail(id);
    }

    @Transactional(readOnly = true)
    public List<Food> adminFoods(UUID admin) {
        requireAdmin(admin);
        return jdbc.query("SELECT id FROM fitness.nutrition_catalog_foods ORDER BY updated_at DESC,id DESC LIMIT 100",
                (rs, n) -> find((UUID) rs.getObject(1)));
    }

    private void insertRevision(UUID actor, UUID foodId, int number, RevisionInput input) {
        UUID revision = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.nutrition_catalog_revisions
                  (id,food_id,revision_number,name,energy_kcal_per_100g,protein_g_per_100g,
                   carbohydrate_g_per_100g,fat_g_per_100g,source_record_version,source_url,reviewed_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, revision, foodId, number, input.name().trim(), input.energyKcalPer100g(),
                input.proteinGPer100g(), input.carbohydrateGPer100g(), input.fatGPer100g(),
                input.sourceRecordVersion().trim(), input.sourceUrl().trim(), actor);
        for (PortionInput portion : input.portions()) {
            jdbc.update("""
                    INSERT INTO fitness.nutrition_catalog_portions
                      (revision_id,label,gram_factor,grams_per_ml,factor_source,ml_factor_source)
                    VALUES (?,?,?,?,?,?)
                    """, revision, portion.label().trim(), portion.gramsPerServing(),
                    portion.gramsPerMl(), portion.factorSource(), portion.mlFactorSource());
        }
        jdbc.update("UPDATE fitness.nutrition_catalog_foods SET current_revision_id=? WHERE id=?",
                revision, foodId);
    }

    private Food lock(UUID id) {
        jdbc.query("SELECT id FROM fitness.nutrition_catalog_foods WHERE id=? FOR UPDATE",
                rs -> { while (rs.next()) { /* row lock */ } }, id);
        return find(id);
    }

    private Food find(UUID id) {
        var found = jdbc.query("""
                SELECT f.id,f.fdc_id,f.data_type,f.source_name,f.source_release,f.source_date,
                       f.status,f.aggregate_version,r.id,r.revision_number,r.name,
                       r.energy_kcal_per_100g,r.protein_g_per_100g,r.carbohydrate_g_per_100g,
                       r.fat_g_per_100g,r.source_record_version,r.source_url
                FROM fitness.nutrition_catalog_foods f
                JOIN fitness.nutrition_catalog_revisions r ON r.id=f.current_revision_id
                WHERE f.id=?
                """, (rs, n) -> new Food((UUID) rs.getObject(1), rs.getLong(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getObject(6, java.time.LocalDate.class),
                rs.getString(7), rs.getLong(8), (UUID) rs.getObject(9), rs.getInt(10),
                rs.getString(11), rs.getBigDecimal(12), rs.getBigDecimal(13), rs.getBigDecimal(14),
                rs.getBigDecimal(15), rs.getString(16), rs.getString(17), List.of()), id);
        if (found.isEmpty()) throw fail("NUTRITION_CATALOG_NOT_FOUND", 404);
        Food f = found.getFirst();
        var portions = jdbc.query("""
                SELECT id,label,gram_factor,grams_per_ml,factor_source,ml_factor_source
                FROM fitness.nutrition_catalog_portions WHERE revision_id=? ORDER BY label,id
                """, (rs, n) -> new Portion((UUID) rs.getObject(1), rs.getString(2),
                rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getString(5), rs.getString(6)), f.revisionId());
        return new Food(f.id(), f.fdcId(), f.dataType(), f.sourceName(), f.sourceRelease(),
                f.sourceDate(), f.status(), f.aggregateVersion(), f.revisionId(),
                f.revisionNumber(), f.name(), f.energyKcalPer100g(), f.proteinGPer100g(),
                f.carbohydrateGPer100g(), f.fatGPer100g(), f.sourceRecordVersion(),
                f.sourceUrl(), portions);
    }

    private void requireAdmin(UUID actor) {
        if (actor == null || accounts.getAccountStatus(actor).orElse(null) != AccountStatus.ACTIVE
                || !roles.hasActiveRole(actor, "ADMIN")
                || !permissions.hasActivePermission(actor, "CATALOG_MANAGE"))
            throw fail("NUTRITION_CATALOG_ACCESS_DENIED", 403);
    }

    private static void validateRevision(long fdcId, RevisionInput input) {
        if (input == null || blank(input.name()) || input.name().length() > 200
                || blank(input.sourceRecordVersion()) || input.sourceRecordVersion().length() > 80
                || blank(input.sourceUrl())
                || !List.of("https://fdc.nal.usda.gov/food-details/" + fdcId + "/nutrients",
                        "https://api.nal.usda.gov/fdc/v1/food/" + fdcId).contains(input.sourceUrl().trim())
                || input.portions() == null || input.portions().size() > 50)
            throw fail("VALIDATION_FAILED", 400);
        for (BigDecimal value : List.of(input.energyKcalPer100g() == null ? BigDecimal.ZERO : input.energyKcalPer100g(),
                input.proteinGPer100g() == null ? BigDecimal.ZERO : input.proteinGPer100g(),
                input.carbohydrateGPer100g() == null ? BigDecimal.ZERO : input.carbohydrateGPer100g(),
                input.fatGPer100g() == null ? BigDecimal.ZERO : input.fatGPer100g()))
            if (value.signum() < 0 || value.scale() > 6 || value.precision() > 18)
                throw fail("VALIDATION_FAILED", 400);
        for (PortionInput portion : input.portions())
            if (portion == null || blank(portion.label()) || portion.label().length() > 100
                    || portion.gramsPerServing() != null && !positiveFactor(portion.gramsPerServing())
                    || portion.gramsPerMl() != null && !positiveFactor(portion.gramsPerMl())
                    || portion.gramsPerServing() != null && blank(portion.factorSource())
                    || portion.gramsPerMl() != null && blank(portion.mlFactorSource()))
                throw fail("VALIDATION_FAILED", 400);
    }
    private static boolean positiveFactor(BigDecimal value) {
        return value.signum() > 0 && value.scale() <= 9 && value.precision() <= 18;
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static NutritionFailure fail(String code, int status) { return new NutritionFailure(code, status); }
}
