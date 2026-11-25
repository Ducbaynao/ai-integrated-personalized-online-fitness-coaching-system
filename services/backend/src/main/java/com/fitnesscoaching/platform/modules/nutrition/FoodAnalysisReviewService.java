package com.fitnesscoaching.platform.modules.nutrition;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.fitnesscoaching.platform.modules.nutrition.FoodTextAnalysisController.*;

/** Student-owned review and atomic selected-item confirmation of ESTIMATE drafts. */
@Service
public class FoodAnalysisReviewService {
    private final JdbcTemplate jdbc;
    private final NutritionStrategyCommands commands;
    private final NutritionCatalogService catalog;
    private final FoodTextAnalysisService analyses;
    private final NutritionBasicService nutrition;
    private final ObjectMapper mapper;

    FoodAnalysisReviewService(JdbcTemplate jdbc, NutritionStrategyCommands commands,
            NutritionCatalogService catalog, FoodTextAnalysisService analyses,
            NutritionBasicService nutrition, ObjectMapper mapper) {
        this.jdbc = jdbc; this.commands = commands; this.catalog = catalog;
        this.analyses = analyses;
        this.nutrition = nutrition; this.mapper = mapper;
    }

    public record Nutrients(BigDecimal energyKcal, BigDecimal proteinG,
                            BigDecimal carbohydrateG, BigDecimal fatG) {}
    public record PreviewItem(FoodAnalysisSelectedItem selection, Nutrients nutrients, String catalogSource) {}
    public record Preview(UUID previewId, long previewRevision, UUID draftId, long draftRevision,
                          List<PreviewItem> items, Nutrients totalNutrients, Instant createdAt) {}
    public record PreviewResult(Preview preview, boolean created) {}
    public record FoodAnalysisConfirmedItem(UUID itemId, long itemRevision, UUID foodLogItemId, UUID mealId,
            UUID foodId, UUID catalogRevisionId, BigDecimal quantity, String quantityBasis, UUID portionId,
            Nutrients nutrients, String catalogSource) {}
    public record FoodAnalysisConfirmationReceipt(UUID receiptId, String commandKey, UUID draftId,
            Instant committedAt, boolean replayed, List<FoodAnalysisConfirmedItem> confirmedItems,
            long resultingDraftRevision, List<UUID> remainingDraftItemIds) {}
    private record CorrectionIdentity(UUID draftId, UUID itemId, FoodAnalysisItemCorrectionRequest request) {}
    private record PreviewIdentity(UUID draftId, FoodAnalysisPreviewRequest request) {}
    private record ConfirmationIdentity(UUID draftId, FoodAnalysisConfirmRequest request) {}
    private record State(long revision, String status, Instant expiresAt, LocalDate day,
                         String timezone, Instant consumedAt, String mealType, UUID mealId) {}
    private record Selection(long revision, UUID food, UUID catalogRevision, BigDecimal quantity,
                             String basis, UUID portion, UUID confirmed) {}

    @Transactional
    public FoodTextAnalysisService.Draft correct(UUID actor, UUID draftId, UUID itemId,
                                                FoodAnalysisItemCorrectionRequest input) {
        commands.requireStudent(actor);
        commands.lockStudent(actor);
        commands.requireStudent(actor);
        State state = owner(actor, draftId);
        BigDecimal quantity = input.quantity() == null ? null : NutritionCatalogCalculation.quantity(input.quantity());
        var request = new FoodAnalysisItemCorrectionRequest(input.commandKey(), input.expectedDraftRevision(),
                input.expectedItemRevision(), input.selectedFoodId(), input.selectedRevisionId(), quantity,
                input.quantityBasis(), input.portionId());
        return commands.receipt(actor, input.commandKey(), "FOOD_ANALYSIS_CORRECT",
                new CorrectionIdentity(draftId, itemId, request), FoodTextAnalysisService.Draft.class, () -> {
                    ready(state, input.expectedDraftRevision());
                    Selection item = selection(draftId, itemId);
                    editable(item, input.expectedItemRevision());
                    validateSelection(request.selectedFoodId(), request.selectedRevisionId(),
                            request.quantityBasis(), request.portionId());
                    jdbc.update("""
                            UPDATE fitness.food_analysis_draft_items SET selected_food_id=?,selected_revision_id=?,
                              quantity=?,quantity_basis=?,portion_id=?,item_revision=item_revision+1,updated_at=clock_timestamp()
                            WHERE request_id=? AND id=?
                            """, request.selectedFoodId(), request.selectedRevisionId(), quantity,
                            request.quantityBasis(), request.portionId(), draftId, itemId);
                    jdbc.update("""
                            UPDATE fitness.food_analysis_requests SET draft_revision=draft_revision+1,
                              updated_at=clock_timestamp() WHERE id=?
                            """, draftId);
                    commands.audit(actor, "STUDENT", "FOOD_ANALYSIS_ITEM_CORRECTED", "FOOD_ANALYSIS_REQUEST",
                            draftId, Map.of("itemId", itemId));
                    return analyses.detail(actor, draftId);
                });
    }

    @Transactional
    public PreviewResult preview(UUID actor, UUID draftId, FoodAnalysisPreviewRequest input) {
        commands.requireStudent(actor);
        commands.lockStudent(actor);
        commands.requireStudent(actor);
        State state = owner(actor, draftId);
        var selected = canonicalItems(input.selectedItems());
        var canonical = new FoodAnalysisPreviewRequest(input.commandKey(), input.expectedDraftRevision(),
                input.day(), input.timezone(), input.consumedAt(), input.mealType(), selected);
        var created = new AtomicBoolean();
        Preview result = commands.receipt(actor, input.commandKey(), "FOOD_ANALYSIS_PREVIEW",
                new PreviewIdentity(draftId, canonical), Preview.class, () -> {
                    ready(state, input.expectedDraftRevision());
                    context(state, input.day(), input.timezone(), input.consumedAt(), input.mealType());
                    var items = checkedItems(draftId, selected).stream().map(Checked::preview).toList();
                    Long revision = jdbc.queryForObject("""
                            SELECT COALESCE(MAX(preview_revision),0)+1 FROM fitness.food_analysis_previews
                            WHERE request_id=?
                            """, Long.class, draftId);
                    var snapshot = new Preview(UUID.randomUUID(), revision, draftId, state.revision(),
                            items, total(items), commands.databaseNow());
                    jdbc.update("""
                            INSERT INTO fitness.food_analysis_previews
                              (id,request_id,preview_revision,draft_revision,command_key,payload_hash,
                               selection_json,nutrients_json,created_at)
                            VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?)
                            """, snapshot.previewId(), draftId, revision, state.revision(), input.commandKey(),
                            fingerprint(commands.json(new PreviewIdentity(draftId, canonical))),
                            commands.json(selected), commands.json(snapshot), Timestamp.from(snapshot.createdAt()));
                    commands.audit(actor, "STUDENT", "FOOD_ANALYSIS_PREVIEW_CREATED", "FOOD_ANALYSIS_REQUEST",
                            draftId, Map.of("previewId", snapshot.previewId()));
                    created.set(true);
                    return snapshot;
                });
        return new PreviewResult(result, created.get());
    }

    private List<FoodAnalysisSelectedItem> canonicalItems(List<FoodAnalysisSelectedItem> input) {
        if (input == null || input.isEmpty()) throw fail("VALIDATION_FAILED", 400);
        var seen = new HashSet<UUID>();
        var selected = new ArrayList<FoodAnalysisSelectedItem>();
        for (var item : input) {
            if (item == null || item.itemId() == null || item.itemRevision() == null || item.itemRevision() < 0
                    || item.foodId() == null || item.catalogRevisionId() == null || !seen.add(item.itemId()))
                throw fail("VALIDATION_FAILED", 400);
            NutritionCatalogCalculation.basis(item.quantityBasis());
            selected.add(new FoodAnalysisSelectedItem(item.itemId(), item.itemRevision(), item.foodId(),
                    item.catalogRevisionId(), NutritionCatalogCalculation.quantity(item.quantity()),
                    item.quantityBasis(), item.portionId()));
        }
        selected.sort(Comparator.comparing(s -> s.itemId().toString()));
        return List.copyOf(selected);
    }

    private record Checked(NutritionCatalogService.Food food, NutritionCatalogCalculation.Calculation calculation,
                           PreviewItem preview) {}

    private List<Checked> checkedItems(UUID draftId, List<FoodAnalysisSelectedItem> selected) {
                    for (var item : selected) {
                        Selection saved = selection(draftId, item.itemId());
                        editable(saved, item.itemRevision());
                        if (!Objects.equals(saved.food(), item.foodId())
                                || !Objects.equals(saved.catalogRevision(), item.catalogRevisionId())
                                || saved.quantity() == null || saved.quantity().compareTo(item.quantity()) != 0
                                || !Objects.equals(saved.basis(), item.quantityBasis())
                                || !Objects.equals(saved.portion(), item.portionId()))
                            throw fail("NUTRITION_VERSION_STALE", 409);
                    }
                    // All writers lock their food row exclusively; stable order prevents multi-food deadlocks.
                    var foods = new HashMap<UUID, NutritionCatalogService.Food>();
                    selected.stream().map(FoodAnalysisSelectedItem::foodId).distinct()
                            .sorted(Comparator.comparing(UUID::toString))
                            .forEach(id -> foods.put(id, catalog.publishedForLogging(id)));
                    var items = new ArrayList<Checked>();
                    for (var item : selected) {
                        var food = foods.get(item.foodId());
                        checkRevision(food, item.catalogRevisionId(), item.portionId());
                        var calculation = NutritionCatalogCalculation.calculate(food, item.quantity(),
                                item.quantityBasis(), item.portionId());
                        var calculated = calculation.nutrients();
                        var nutrients = new Nutrients(calculated.energyKcal(), calculated.proteinG(),
                                calculated.carbohydrateG(), calculated.fatG());
                        // Pinned source and revision, not AI-provided nutrition or a mutable display label.
                        String source = commands.json(Map.of("sourceName", food.sourceName(),
                                "sourceRelease", food.sourceRelease(), "fdcId", food.fdcId(),
                                "revisionId", food.revisionId(), "sourceRecordVersion", food.sourceRecordVersion(),
                                "sourceUrl", food.sourceUrl()));
                        items.add(new Checked(food, calculation, new PreviewItem(item, nutrients, source)));
                    }
        return items;
    }

    private State owner(UUID actor, UUID draftId) {
        var rows = jdbc.query("""
                SELECT draft_revision,status,expires_at,log_date,timezone,consumed_at,meal_type,meal_id
                FROM fitness.food_analysis_requests WHERE id=? AND student_id=? AND command_key IS NOT NULL FOR UPDATE
                """, (rs, n) -> new State(rs.getLong(1), rs.getString(2),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(),
                rs.getDate(4).toLocalDate(), rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getString(7),
                (UUID) rs.getObject(8)), draftId, actor);
        if (rows.isEmpty()) throw fail("NUTRITION_ANALYSIS_NOT_FOUND", 404);
        return rows.getFirst();
    }

    @Transactional
    public FoodAnalysisConfirmationReceipt confirm(UUID actor, UUID draftId, FoodAnalysisConfirmRequest input) {
        commands.requireStudent(actor);
        commands.lockStudent(actor);
        commands.requireStudent(actor);
        State state = owner(actor, draftId);
        var selected = canonicalItems(input.selectedItems());
        var canonical = new FoodAnalysisConfirmRequest(input.commandKey(), input.expectedDraftRevision(),
                input.previewId(), input.expectedPreviewRevision(), input.day(), input.timezone(), input.consumedAt(),
                input.mealType(), selected);
        var created = new AtomicBoolean();
        var receipt = commands.receipt(actor, input.commandKey(), "FOOD_ANALYSIS_CONFIRM",
                new ConfirmationIdentity(draftId, canonical), FoodAnalysisConfirmationReceipt.class, receiptId -> {
                    ready(state, input.expectedDraftRevision());
                    context(state, input.day(), input.timezone(), input.consumedAt(), input.mealType());
                    Preview preview = confirmationPreview(draftId, state.revision(), input);
                    if (!canonicalItems(preview.items().stream().map(PreviewItem::selection).toList()).equals(selected))
                        throw fail("NUTRITION_PREVIEW_STALE", 409);
                    List<Checked> checked;
                    try { checked = checkedItems(draftId, selected); }
                    catch (NutritionFailure failure) {
                        if ("NUTRITION_CATALOG_NOT_FOUND".equals(failure.code()))
                            throw fail("NUTRITION_CATALOG_VERSION_CONFLICT", 409);
                        throw failure;
                    }
                    Map<UUID, PreviewItem> viewed = new HashMap<>();
                    preview.items().forEach(item -> viewed.put(item.selection().itemId(), item));
                    for (var item : checked) {
                        var prior = viewed.get(item.preview().selection().itemId());
                        if (!sameNutrients(prior.nutrients(), item.preview().nutrients())
                                || !mapper.readTree(prior.catalogSource()).equals(mapper.readTree(item.preview().catalogSource())))
                            throw fail("NUTRITION_PREVIEW_STALE", 409);
                    }
                    if (!sameNutrients(preview.totalNutrients(), total(checked.stream().map(Checked::preview).toList())))
                        throw fail("NUTRITION_PREVIEW_STALE", 409);
                    // All sources are locked and validated before the first domain write.
                    nutrition.prepareAnalysisDay(actor, state.day(), state.timezone());
                    UUID mealId = confirmationMeal(actor, state);
                    var confirmed = new ArrayList<FoodAnalysisConfirmedItem>();
                    Instant committedAt = commands.databaseNow();
                    for (var item : checked) {
                        var selection = item.preview().selection();
                        UUID logId = UUID.randomUUID();
                        String unit = switch (selection.quantityBasis()) {
                            case "GRAM" -> "B07_GRAM";
                            case "MILLILITRE" -> "B07_ML";
                            default -> "B07_SERVING";
                        };
                        var portion = item.food().portions().stream()
                                .filter(p -> p.id().equals(selection.portionId())).findFirst().orElse(null);
                        String provenance = commands.json(Map.of("analysisDraftId", draftId,
                                "draftItemId", selection.itemId(), "previewId", preview.previewId(),
                                "previewRevision", preview.previewRevision(), "receiptId", receiptId,
                                "confirmedBy", actor, "catalogSource", viewed.get(selection.itemId()).catalogSource()));
                        int inserted = jdbc.update("""
                                INSERT INTO fitness.food_log_items
                                  (id,meal_id,food_name_snapshot,quantity,unit_id,gram_weight,entry_source,
                                   confirmed_by_user,confirmed_at,entry_kind,nutrition_catalog_revision_id,
                                   nutrition_catalog_portion_id,quantity_basis,serving_label_snapshot,notes)
                                SELECT ?,?,?,?,u.id,?,'TEXT_AI',true,?,'CATALOG_QUANTIFIED',?,?,?,?,?
                                FROM fitness.measurement_units u WHERE u.code=?
                                """, logId, mealId, item.food().name(), selection.quantity(), item.calculation().grams(),
                                Timestamp.from(committedAt), selection.catalogRevisionId(), selection.portionId(),
                                selection.quantityBasis(), portion == null ? null : portion.label(), provenance, unit);
                        if (inserted != 1) throw fail("NUTRITION_CONVERSION_UNKNOWN", 409);
                        var amounts = viewed.get(selection.itemId()).nutrients();
                        writeNutrients(logId, amounts);
                        jdbc.update("""
                                UPDATE fitness.food_analysis_draft_items SET confirmed_food_log_item_id=?,
                                  item_revision=item_revision+1,updated_at=clock_timestamp() WHERE request_id=? AND id=?
                                """, logId, draftId, selection.itemId());
                        confirmed.add(new FoodAnalysisConfirmedItem(selection.itemId(), selection.itemRevision()+1,
                                logId, mealId, selection.foodId(), selection.catalogRevisionId(), selection.quantity(),
                                selection.quantityBasis(), selection.portionId(), amounts, viewed.get(selection.itemId()).catalogSource()));
                    }
                    var remaining = jdbc.query("""
                            SELECT id FROM fitness.food_analysis_draft_items
                            WHERE request_id=? AND confirmed_food_log_item_id IS NULL ORDER BY item_order,id
                            """, (rs, n) -> (UUID) rs.getObject(1), draftId);
                    jdbc.update("""
                            UPDATE fitness.food_analysis_requests SET draft_revision=draft_revision+1,meal_id=?,
                              status=?,updated_at=clock_timestamp(),
                              completed_at=CASE WHEN ? THEN clock_timestamp() ELSE completed_at END WHERE id=?
                            """, mealId, remaining.isEmpty() ? "CONFIRMED" : "READY_FOR_CONFIRMATION", remaining.isEmpty(), draftId);
                    nutrition.recalculateAnalysisDay(actor, state.day());
                    commands.audit(actor, "STUDENT", "FOOD_ANALYSIS_ITEMS_CONFIRMED", "FOOD_ANALYSIS_REQUEST",
                            draftId, Map.of("receiptId", receiptId, "previewId", preview.previewId(),
                                    "foodLogItemIds", confirmed.stream().map(FoodAnalysisConfirmedItem::foodLogItemId).toList()));
                    created.set(true);
                    return new FoodAnalysisConfirmationReceipt(receiptId, input.commandKey(), draftId, committedAt,
                            false, List.copyOf(confirmed), state.revision()+1, List.copyOf(remaining));
                });
        return new FoodAnalysisConfirmationReceipt(receipt.receiptId(), receipt.commandKey(), receipt.draftId(),
                receipt.committedAt(), !created.get(), receipt.confirmedItems(), receipt.resultingDraftRevision(),
                receipt.remainingDraftItemIds());
    }

    private Preview confirmationPreview(UUID draftId, long revision, FoodAnalysisConfirmRequest input) {
        if (input.previewId() == null || input.expectedPreviewRevision() == null || input.expectedPreviewRevision() < 1)
            throw fail("VALIDATION_FAILED", 400);
        var rows = jdbc.query("""
                SELECT nutrients_json::text,command_key,payload_hash FROM fitness.food_analysis_previews
                WHERE request_id=? AND id=? AND draft_revision=? AND preview_revision=?
                """, (rs, n) -> new StoredPreview(rs.getString(1),rs.getString(2),rs.getString(3)),
                draftId, input.previewId(), revision, input.expectedPreviewRevision());
        if (rows.isEmpty()) throw fail("NUTRITION_PREVIEW_STALE", 409);
        var stored = rows.getFirst();
        var viewedIntent = new FoodAnalysisPreviewRequest(stored.key(), revision, input.day(), input.timezone(),
                input.consumedAt(), input.mealType(), canonicalItems(input.selectedItems()));
        if (!stored.hash().equals(fingerprint(commands.json(new PreviewIdentity(draftId, viewedIntent)))))
            throw fail("NUTRITION_PREVIEW_STALE", 409);
        var preview = mapper.readValue(stored.snapshot(), Preview.class);
        if (!preview.draftId().equals(draftId) || preview.draftRevision() != revision
                || !preview.previewId().equals(input.previewId()) || preview.previewRevision() != input.expectedPreviewRevision())
            throw fail("NUTRITION_PREVIEW_STALE", 409);
        return preview;
    }

    private record StoredPreview(String snapshot, String key, String hash) {}

    private UUID confirmationMeal(UUID actor, State state) {
        if (state.mealId() == null) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.meals(id,student_id,meal_type,consumed_at,created_by,log_date,timezone)
                    VALUES (?,?,?::fitness.meal_type,?,?,?,?)
                    """, id, actor, state.mealType(), Timestamp.from(state.consumedAt()), actor, state.day(), state.timezone());
            return id;
        }
        var rows = jdbc.query("""
                SELECT id FROM fitness.meals WHERE id=? AND student_id=? AND created_by=? AND deleted_at IS NULL
                AND log_date=? AND timezone=? AND consumed_at=? AND meal_type=?::fitness.meal_type FOR UPDATE
                """, (rs, n) -> (UUID) rs.getObject(1), state.mealId(), actor, actor, state.day(), state.timezone(),
                Timestamp.from(state.consumedAt()), state.mealType());
        if (rows.isEmpty()) throw fail("NUTRITION_ANALYSIS_STATE_CONFLICT", 409);
        return rows.getFirst();
    }

    private void writeNutrients(UUID logId, Nutrients nutrients) {
        var amounts = Arrays.asList(nutrients.energyKcal(), nutrients.proteinG(), nutrients.carbohydrateG(), nutrients.fatG());
        var codes = List.of("B07_ENERGY_KCAL", "B07_PROTEIN_G", "B07_CARBOHYDRATE_G", "B07_FAT_G");
        for (int i=0; i<codes.size(); i++) {
            if (amounts.get(i) == null) continue;
            int inserted = jdbc.update("""
                    INSERT INTO fitness.food_log_item_nutrients(food_log_item_id,nutrient_id,amount,unit_id,calculation_source)
                    SELECT ?,n.id,?,n.default_unit_id,'CATALOG' FROM fitness.nutrients n WHERE n.code=?
                    """, logId, amounts.get(i), codes.get(i));
            if (inserted != 1) throw fail("NUTRITION_CONVERSION_UNKNOWN", 409);
        }
    }

    private static boolean sameNutrients(Nutrients a, Nutrients b) {
        return same(a.energyKcal(),b.energyKcal()) && same(a.proteinG(),b.proteinG())
                && same(a.carbohydrateG(),b.carbohydrateG()) && same(a.fatG(),b.fatG());
    }
    private static boolean same(BigDecimal a, BigDecimal b) { return a == null ? b == null : b != null && a.compareTo(b) == 0; }
    private void context(State state, LocalDate day, String timezone, Instant consumedAt, String mealType) {
        if (!Objects.equals(state.day(), day) || !Objects.equals(state.timezone(), timezone)
                || !Objects.equals(state.consumedAt(), consumedAt) || !Objects.equals(state.mealType(), mealType))
            throw fail("NUTRITION_ANALYSIS_CONTEXT_CONFLICT", 409);
    }

    private void ready(State state, Long expected) {
        if (expected == null || expected < 0) throw fail("VALIDATION_FAILED", 400);
        if (!"READY_FOR_CONFIRMATION".equals(state.status())) throw fail("NUTRITION_ANALYSIS_STATE_CONFLICT", 409);
        if (state.expiresAt() != null && !state.expiresAt().isAfter(commands.databaseNow()))
            throw fail("NUTRITION_ANALYSIS_EXPIRED", 409);
        if (state.revision() != expected) throw fail("NUTRITION_VERSION_STALE", 409);
    }

    private Selection selection(UUID draftId, UUID itemId) {
        var rows = jdbc.query("""
                SELECT item_revision,selected_food_id,selected_revision_id,quantity,quantity_basis,portion_id,
                  confirmed_food_log_item_id FROM fitness.food_analysis_draft_items WHERE request_id=? AND id=? FOR UPDATE
                """, (rs, n) -> new Selection(rs.getLong(1), (UUID) rs.getObject(2), (UUID) rs.getObject(3),
                rs.getBigDecimal(4), rs.getString(5), (UUID) rs.getObject(6), (UUID) rs.getObject(7)), draftId, itemId);
        if (rows.isEmpty()) throw fail("NUTRITION_ITEM_NOT_FOUND", 404);
        return rows.getFirst();
    }

    private void editable(Selection selection, Long revision) {
        if (revision == null || revision < 0) throw fail("VALIDATION_FAILED", 400);
        if (selection.confirmed() != null) throw fail("NUTRITION_ANALYSIS_STATE_CONFLICT", 409);
        if (selection.revision() != revision) throw fail("NUTRITION_VERSION_STALE", 409);
    }

    private void validateSelection(UUID foodId, UUID revisionId, String basis, UUID portion) {
        if ((foodId == null) != (revisionId == null) || (portion != null && revisionId == null))
            throw fail("VALIDATION_FAILED", 400);
        if (basis != null) NutritionCatalogCalculation.basis(basis);
        if ("GRAM".equals(basis) && portion != null) throw fail("VALIDATION_FAILED", 400);
        if (foodId != null) checkRevision(catalog.publishedForLogging(foodId), revisionId, portion);
    }

    private void checkRevision(NutritionCatalogService.Food food, UUID revision, UUID portion) {
        if (!food.revisionId().equals(revision)) throw fail("NUTRITION_CATALOG_VERSION_CONFLICT", 409);
        if (portion != null && food.portions().stream().noneMatch(p -> p.id().equals(portion)))
            throw fail("NUTRITION_CATALOG_VERSION_CONFLICT", 409);
    }

    private static Nutrients total(List<PreviewItem> items) {
        return new Nutrients(sum(items, Nutrients::energyKcal), sum(items, Nutrients::proteinG),
                sum(items, Nutrients::carbohydrateG), sum(items, Nutrients::fatG));
    }
    private static BigDecimal sum(List<PreviewItem> items, java.util.function.Function<Nutrients, BigDecimal> field) {
        BigDecimal result = BigDecimal.ZERO;
        for (var item : items) {
            BigDecimal value = field.apply(item.nutrients());
            if (value == null) return null;
            result = result.add(value);
        }
        return result;
    }
    private static String fingerprint(String json) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static NutritionFailure fail(String code, int status) { return new NutritionFailure(code, status); }
}
