package com.fitnesscoaching.platform.modules.nutrition;

import com.fitnesscoaching.platform.modules.ai.application.port.out.F06AiServiceClient;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** First F06 runtime slice: durable, owner-scoped generation and readback only. */
@Service
public class FoodTextAnalysisService {
    private static final Set<String> MEAL_TYPES = Set.of("BREAKFAST", "LUNCH", "DINNER", "SNACK", "OTHER");
    private final JdbcTemplate jdbc;
    private final UserAccountStatusQuery accounts;
    private final StudentCapabilityQuery students;
    private final F06AiServiceClient ai;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    @Value("${ai.f06.processing-timeout-seconds:300}")
    private long processingTimeoutSeconds = 300;

    public FoodTextAnalysisService(JdbcTemplate jdbc, UserAccountStatusQuery accounts,
            StudentCapabilityQuery students, F06AiServiceClient ai, AuditService audit,
            ObjectMapper mapper, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.students = students;
        this.ai = ai;
        this.audit = audit;
        this.mapper = mapper;
        this.transactions = transactions;
    }

    public record Candidate(UUID foodId, UUID revisionId, String name) {}
    public record Item(UUID itemId, long itemRevision, String detectedName,
            List<Candidate> candidates, UUID selectedFoodId, UUID selectedRevisionId,
            BigDecimal quantity, String quantityBasis, UUID portionId, BigDecimal confidence,
            List<String> missingData, List<String> limitations, UUID confirmedFoodLogItemId) {}
    public record Draft(UUID draftId, UUID studentId, String commandKey, String resultType,
            String analysisStatus, String confirmationStatus, long draftRevision, LocalDate day,
            String timezone, Instant consumedAt, String mealType, UUID runId, Instant expiresAt,
            List<Item> items, Object latestPreview, Instant createdAt, Instant updatedAt) {}
    public record Created(Draft draft, boolean created) {}
    private record Start(UUID id, boolean created) {}

    public Created create(UUID actor, FoodTextAnalysisController.CreateRequest request) {
        requireStudent(actor);
        validate(request);
        String text = request.text().trim();
        String fingerprint = sha(json(List.of(text, request.day(), request.timezone(),
                request.consumedAt(), request.mealType())));
        Start start = transactions.execute(ignored -> {
            lockStudent(actor);
            // Generate is durable before provider I/O; prevent collision with committed Nutrition actions.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 709))",
                    rs -> { while (rs.next()) { /* receipt namespace */ } }, actor.toString());
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM fitness.nutrition_command_receipts WHERE actor_id=? AND command_key=?)
                    """, Boolean.class, actor, request.commandKey())))
                throw new NutritionFailure("NUTRITION_IDEMPOTENCY_CONFLICT", 409);
            var prior = jdbc.query("""
                    SELECT id,request_fingerprint FROM fitness.food_analysis_requests
                    WHERE student_id=? AND command_key=? FOR UPDATE
                    """, (rs, n) -> new StartRow((UUID) rs.getObject(1), rs.getString(2)),
                    actor, request.commandKey());
            if (!prior.isEmpty()) {
                if (!fingerprint.equals(prior.getFirst().fingerprint()))
                    throw new NutritionFailure("NUTRITION_IDEMPOTENCY_CONFLICT", 409);
                return new Start(prior.getFirst().id(), false);
            }
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.food_analysis_requests
                      (id,student_id,input_type,input_text,status,command_key,request_fingerprint,
                       log_date,timezone,consumed_at,meal_type)
                    VALUES (?,?,'TEXT',?,'PROCESSING',?,?,?,?,?,?::fitness.meal_type)
                    """, id, actor, text, request.commandKey(), fingerprint, request.day(),
                    request.timezone(), Timestamp.from(request.consumedAt()), request.mealType());
            audit(actor, "FOOD_TEXT_ANALYSIS_STARTED", id);
            return new Start(id, true);
        });
        if (start == null) throw new IllegalStateException("F06 transaction produced no draft");
        if (!start.created()) return new Created(detail(actor, start.id()), false);

        // Never call the provider inside the request-identity transaction.
        String estimate;
        try {
            estimate = ai.estimateTextJson(text);
        } catch (RuntimeException unavailable) {
            transactions.executeWithoutResult(ignored -> jdbc.update("""
                    UPDATE fitness.food_analysis_requests SET status='FAILED',
                      failure_reason='PROVIDER_UNAVAILABLE',updated_at=clock_timestamp()
                    WHERE id=? AND student_id=? AND status='PROCESSING'
                    """, start.id(), actor));
            return new Created(detail(actor, start.id()), true);
        }

        List<RecognizedItem> parsed;
        try {
            parsed = parseEstimate(estimate);
        } catch (NutritionFailure invalid) {
            markFailed(actor, start.id(), "INVALID_PROVIDER_OUTPUT");
            return new Created(detail(actor, start.id()), true);
        }
        transactions.executeWithoutResult(ignored -> {
            requireStudent(actor);
            lockStudent(actor);
            var states = jdbc.query("""
                    SELECT status FROM fitness.food_analysis_requests
                    WHERE id=? AND student_id=? FOR UPDATE
                    """, (rs, n) -> rs.getString(1), start.id(), actor);
            // A recovered timeout is terminal: a late provider must not publish items.
            if (states.isEmpty() || !"PROCESSING".equals(states.getFirst())) return;
            int order = 0;
            for (RecognizedItem item : parsed) {
                jdbc.update("""
                        INSERT INTO fitness.food_analysis_draft_items
                          (request_id,item_order,detected_name,candidate_names,estimated_quantity,
                           estimated_unit_text,confidence,missing_data,limitations)
                        VALUES (?,?,?,?::jsonb,?,?,?,?::jsonb,?::jsonb)
                        """, start.id(), ++order, item.name(), json(item.candidateNames()),
                        item.quantity(), item.unitText(), item.confidence(),
                        json(item.missingData()), json(item.limitations()));
            }
            jdbc.update("""
                    UPDATE fitness.food_analysis_requests SET status='READY_FOR_CONFIRMATION',
                      draft_revision=1,updated_at=clock_timestamp()
                    WHERE id=? AND student_id=? AND status='PROCESSING'
                    """, start.id(), actor);
            audit(actor, "FOOD_TEXT_ANALYSIS_READY", start.id());
        });
        return new Created(detail(actor, start.id()), true);
    }

    private void markFailed(UUID actor, UUID id, String reason) {
        transactions.executeWithoutResult(ignored -> jdbc.update("""
                UPDATE fitness.food_analysis_requests SET status='FAILED',
                  failure_reason=?,updated_at=clock_timestamp()
                WHERE id=? AND student_id=? AND status='PROCESSING'
                """, reason, id, actor));
    }

    public Draft recover(UUID actor, String commandKey) {
        requireStudent(actor);
        if (commandKey == null || commandKey.isBlank() || commandKey.length() > 120)
            throw new NutritionFailure("VALIDATION_FAILED", 400);
        var ids = jdbc.query("""
                SELECT id FROM fitness.food_analysis_requests WHERE student_id=? AND command_key=?
                """, (rs, n) -> (UUID) rs.getObject(1), actor, commandKey);
        if (ids.isEmpty()) throw new NutritionFailure("NUTRITION_ANALYSIS_NOT_FOUND", 404);
        return detail(actor, ids.getFirst());
    }

    public Draft detail(UUID actor, UUID id) {
        requireStudent(actor);
        return transactions.execute(ignored -> {
            lockStudent(actor);
            requireStudent(actor);
            return readDetail(actor, id);
        });
    }

    private Draft readDetail(UUID actor, UUID id) {
        jdbc.update("""
                UPDATE fitness.food_analysis_requests SET status='FAILED',
                  failure_reason='PROCESSING_TIMEOUT',updated_at=clock_timestamp()
                WHERE id=? AND student_id=? AND command_key IS NOT NULL AND status='PROCESSING'
                  AND updated_at < clock_timestamp() - (? * INTERVAL '1 second')
                """, id, actor, Math.max(1, processingTimeoutSeconds));
        var rows = jdbc.query("""
                SELECT * FROM fitness.food_analysis_requests WHERE id=? AND student_id=? AND command_key IS NOT NULL
                """, (rs, n) -> draftRow(rs), id, actor);
        if (rows.isEmpty()) throw new NutritionFailure("NUTRITION_ANALYSIS_NOT_FOUND", 404);
        DraftRow row = rows.getFirst();
        List<Item> items = jdbc.query("""
                SELECT * FROM fitness.food_analysis_draft_items
                WHERE request_id=? ORDER BY item_order
                """, (rs, n) -> new Item((UUID) rs.getObject("id"), rs.getLong("item_revision"),
                        rs.getString("detected_name"), List.of(),
                        (UUID) rs.getObject("selected_food_id"),
                        (UUID) rs.getObject("selected_revision_id"), rs.getBigDecimal("quantity"),
                        rs.getString("quantity_basis"), (UUID) rs.getObject("portion_id"),
                        rs.getBigDecimal("confidence"), strings(rs.getString("missing_data")),
                        strings(rs.getString("limitations")),
                        (UUID) rs.getObject("confirmed_food_log_item_id")), id);
        long confirmed = items.stream().filter(i -> i.confirmedFoodLogItemId() != null).count();
        String confirmation = confirmed == 0 ? "DRAFT"
                : confirmed == items.size() ? "CONFIRMED" : "PARTIALLY_CONFIRMED";
        String analysis = switch (row.status()) {
            case "READY_FOR_CONFIRMATION", "CONFIRMED" -> "READY";
            case "FAILED" -> "FAILED";
            default -> "PROCESSING";
        };
        return new Draft(id, actor, row.commandKey(), "ESTIMATE", analysis, confirmation,
                row.revision(), row.day(), row.timezone(), row.consumedAt(), row.mealType(),
                row.runId(), row.expiresAt(), items, latestPreview(id, row.revision()), row.createdAt(), row.updatedAt());
    }

    private FoodAnalysisReviewService.Preview latestPreview(UUID id, long revision) {
        var rows = jdbc.query("""
                SELECT nutrients_json::text FROM fitness.food_analysis_previews
                WHERE request_id=? AND draft_revision=? ORDER BY preview_revision DESC LIMIT 1
                """, (rs, n) -> rs.getString(1), id, revision);
        if (rows.isEmpty()) return null;
        try {
            var preview = mapper.readValue(rows.getFirst(), FoodAnalysisReviewService.Preview.class);
            // Historical receipts remain available, but current detail must not advertise stale catalog selections.
            for (var item : preview.items()) {
                Boolean current = jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM fitness.nutrition_catalog_foods
                        WHERE id=? AND status='PUBLISHED' AND current_revision_id=?)
                        """, Boolean.class, item.selection().foodId(), item.selection().catalogRevisionId());
                if (!Boolean.TRUE.equals(current)) return null;
            }
            return preview;
        } catch (Exception invalid) { throw new IllegalStateException("Invalid stored F06 preview", invalid); }
    }

    List<RecognizedItem> parseEstimate(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            if (root == null || !root.isObject() || root.size() != 4
                    || !"ESTIMATE".equals(root.path("resultType").asText())
                    || !root.path("items").isArray()
                    || root.path("items").size() > 20
                    || !uniqueStringsNode(root.path("missingData"), false)
                    || !stringsNode(root.path("limitations"))) throw new IllegalArgumentException();
            var result = new java.util.ArrayList<RecognizedItem>();
            for (JsonNode node : root.path("items")) {
                if (!node.isObject() || node.size() != 7
                        || !node.path("detectedName").isTextual()
                        || node.path("detectedName").asText().isBlank()
                        || node.path("detectedName").asText().length() > 200
                        || !uniqueStringsNode(node.path("candidateNames"), true)
                        || !uniqueStringsNode(node.path("missingData"), false)
                        || !stringsNode(node.path("limitations"))) throw new IllegalArgumentException();
                JsonNode quantityNode = node.path("quantity");
                JsonNode confidenceNode = node.path("confidence");
                JsonNode unitNode = node.path("unitText");
                BigDecimal quantity = quantityNode.isNull() ? null : quantityNode.decimalValue();
                BigDecimal confidence = confidenceNode.isNull() ? null : confidenceNode.decimalValue();
                if ((!quantityNode.isNull() && (!quantityNode.isNumber() || quantity.signum() <= 0))
                        || (!confidenceNode.isNull() && (!confidenceNode.isNumber()
                            || confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0))
                        || (!unitNode.isNull() && (!unitNode.isTextual()
                            || unitNode.asText().isBlank() || unitNode.asText().length() > 80)))
                    throw new IllegalArgumentException();
                result.add(new RecognizedItem(node.path("detectedName").asText(),
                        textList(node.path("candidateNames")), quantity,
                        unitNode.isNull() ? null : unitNode.asText(), confidence,
                        textList(node.path("missingData")), textList(node.path("limitations"))));
            }
            return result;
        } catch (Exception invalid) {
            throw new NutritionFailure("NUTRITION_AI_OUTPUT_INVALID", 409);
        }
    }

    private static boolean stringsNode(JsonNode node) {
        if (!node.isArray()) return false;
        for (JsonNode value : node) if (!value.isTextual()) return false;
        return true;
    }

    private static boolean uniqueStringsNode(JsonNode node, boolean candidate) {
        if (!stringsNode(node)) return false;
        var seen = new java.util.HashSet<String>();
        for (JsonNode value : node) {
            String text = value.asText();
            int length = text.codePointCount(0, text.length());
            if (!seen.add(text) || (candidate && (length < 1 || length > 200))) return false;
        }
        return true;
    }

    private static List<String> textList(JsonNode node) {
        var result = new java.util.ArrayList<String>();
        for (JsonNode value : node) result.add(value.asText());
        return result;
    }

    private List<String> strings(String json) {
        try { return Arrays.asList(mapper.readValue(json, String[].class)); }
        catch (Exception invalid) { throw new IllegalStateException("Invalid F06 draft JSON", invalid); }
    }

    private DraftRow draftRow(ResultSet rs) throws SQLException {
        return new DraftRow(rs.getString("command_key"), rs.getString("status"),
                rs.getLong("draft_revision"), rs.getDate("log_date").toLocalDate(),
                rs.getString("timezone"), rs.getTimestamp("consumed_at").toInstant(),
                rs.getString("meal_type"), (UUID) rs.getObject("ai_run_id"),
                instant(rs.getTimestamp("expires_at")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void requireStudent(UUID actor) {
        if (actor == null || accounts.getAccountStatus(actor).orElse(null) != AccountStatus.ACTIVE
                || !students.hasProfile(actor)) throw new NutritionFailure("NUTRITION_ACCESS_DENIED", 403);
    }

    private static void validate(FoodTextAnalysisController.CreateRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()
                || request.text().length() > 4000 || request.day() == null
                || request.consumedAt() == null || request.timezone() == null
                || request.timezone().length() > 64 || request.commandKey() == null
                || request.commandKey().isBlank() || request.commandKey().length() > 120
                || !MEAL_TYPES.contains(request.mealType()))
            throw new NutritionFailure("VALIDATION_FAILED", 400);
        ZoneId zone;
        try { zone = ZoneId.of(request.timezone()); }
        catch (Exception invalid) { throw new NutritionFailure("VALIDATION_FAILED", 400); }
        if (!request.consumedAt().atZone(zone).toLocalDate().equals(request.day()))
            throw new NutritionFailure("VALIDATION_FAILED", 400);
    }

    private void lockStudent(UUID actor) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 707))",
                rs -> { while (rs.next()) { /* held through transaction */ } }, actor.toString());
    }

    private void audit(UUID actor, String action, UUID id) {
        audit.recordAudit(AuditRecord.builder().actorUserId(actor).actorRole("STUDENT")
                .action(action).targetType("FOOD_ANALYSIS_REQUEST").targetId(id)
                .beforeDataJson("{}").afterDataJson("{}")
                .occurredAt(Instant.now()).build());
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalStateException("Cannot serialize F06 value", invalid); }
    }

    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private record StartRow(UUID id, String fingerprint) {}
    private record RecognizedItem(String name, List<String> candidateNames, BigDecimal quantity,
            String unitText, BigDecimal confidence, List<String> missingData, List<String> limitations) {}
    private record DraftRow(String commandKey, String status, long revision, LocalDate day,
            String timezone, Instant consumedAt, String mealType, UUID runId, Instant expiresAt,
            Instant createdAt, Instant updatedAt) {}
}
