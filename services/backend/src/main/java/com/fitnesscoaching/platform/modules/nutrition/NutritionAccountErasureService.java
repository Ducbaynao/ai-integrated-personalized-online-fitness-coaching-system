package com.fitnesscoaching.platform.modules.nutrition;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.fitnesscoaching.platform.modules.user.application.port.in.AccountErasureFence;

import java.util.UUID;

@Service
public class NutritionAccountErasureService implements NutritionAccountErasure {
    private final JdbcTemplate jdbc;
    private final AccountErasureFence fence;

    public NutritionAccountErasureService(JdbcTemplate jdbc, AccountErasureFence fence) {
        this.jdbc = jdbc; this.fence = fence;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void minimizeFoodTextAnalyses(UUID userId, UUID requestId) {
        // Account owns the lease/hold fence. Match Nutrition's outer locks before touching its rows.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,707))",
                rs -> { while (rs.next()) { } }, userId.toString());
        if (!fence.lockCleanupAuthorized(userId, requestId))
            throw new IllegalStateException("F06 minimization requires the disabled account's erasure request");
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,709))",
                rs -> { while (rs.next()) { } }, userId.toString());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.food_analysis_requests
                  WHERE student_id=? AND input_type='TEXT' AND command_key IS NOT NULL)
                """, Boolean.class, userId))) return;

        // Mutable receipt bodies can copy a F06 item through later Basic readMine responses.
        // Keep receipt IDs/action/timing only; user-supplied keys/hashes may themselves identify the subject.
        jdbc.update("""
                UPDATE fitness.nutrition_command_receipts SET response_json='{}'::jsonb,
                  payload_hash=repeat('0',64),command_key='erased-'||receipt_id::text
                WHERE actor_id=? AND (command_name IN ('FOOD_ANALYSIS_CORRECT','FOOD_ANALYSIS_PREVIEW',
                  'FOOD_ANALYSIS_CONFIRM','CREATE_FOOD','UPDATE_FOOD','ADD_ITEM','ADD_CATALOG_ITEM','CORRECT_ITEM','CONFIRM_DAY'))
                  AND (response_json<>'{}'::jsonb OR payload_hash<>repeat('0',64) OR command_key<>'erased-'||receipt_id::text)
                """, userId);
        jdbc.update("""
                INSERT INTO fitness.nutrition_f06_erasure_redactions(request_id,correction_id,prior_sha256)
                SELECT ?,c.id,encode(fitness.digest(to_jsonb(c)::text,'sha256'),'hex')
                FROM fitness.nutrition_item_corrections c
                JOIN fitness.food_analysis_draft_items i ON i.confirmed_food_log_item_id=c.food_log_item_id
                JOIN fitness.food_analysis_requests a ON a.id=i.request_id
                WHERE a.student_id=? AND a.input_type='TEXT' AND a.command_key IS NOT NULL
                  AND (c.before_json<>'{}'::jsonb OR c.after_json<>'{}'::jsonb)
                ON CONFLICT(request_id,correction_id) DO NOTHING
                """, requestId, userId);
        jdbc.update("""
                UPDATE fitness.nutrition_item_corrections c SET before_json='{}'::jsonb,after_json='{}'::jsonb
                FROM fitness.nutrition_f06_erasure_redactions e WHERE e.request_id=? AND e.correction_id=c.id
                  AND e.prior_sha256=encode(fitness.digest(to_jsonb(c)::text,'sha256'),'hex')
                """, requestId);
        jdbc.update("""
                DELETE FROM fitness.food_log_item_nutrients n USING fitness.food_analysis_draft_items i,
                  fitness.food_analysis_requests a WHERE a.id=i.request_id AND i.confirmed_food_log_item_id=n.food_log_item_id
                  AND a.student_id=? AND a.input_type='TEXT' AND a.command_key IS NOT NULL
                """, userId);
        jdbc.update("""
                UPDATE fitness.food_log_items l SET food_id=NULL,food_name_snapshot='ERASED',quantity=NULL,unit_id=NULL,
                  gram_weight=NULL,confidence_score=NULL,notes=NULL,private_food_id=NULL,private_food_version=NULL,
                  serving_label_snapshot=NULL,nutrition_catalog_revision_id=NULL,nutrition_catalog_portion_id=NULL,
                  quantity_basis=NULL,entry_kind='DESCRIPTION_ONLY'
                FROM fitness.food_analysis_draft_items i JOIN fitness.food_analysis_requests a ON a.id=i.request_id
                WHERE i.confirmed_food_log_item_id=l.id AND a.student_id=? AND a.input_type='TEXT' AND a.command_key IS NOT NULL
                  AND (l.food_name_snapshot<>'ERASED' OR l.quantity IS NOT NULL OR l.notes IS NOT NULL
                    OR l.entry_kind<>'DESCRIPTION_ONLY' OR l.confidence_score IS NOT NULL OR l.food_id IS NOT NULL)
                """, userId);
        // Drop derived totals for affected days rather than retain a stale personal actual or fabricate zero.
        jdbc.update("""
                DELETE FROM fitness.daily_nutrition_summaries s USING fitness.food_analysis_requests a
                WHERE a.student_id=? AND a.input_type='TEXT' AND a.command_key IS NOT NULL
                  AND s.student_id=a.student_id AND s.summary_date=a.log_date
                """, userId);
        jdbc.update("""
                DELETE FROM fitness.daily_nutrition_log_status s USING fitness.food_analysis_requests a
                WHERE a.student_id=? AND a.input_type='TEXT' AND a.command_key IS NOT NULL
                  AND s.student_id=a.student_id AND s.log_date=a.log_date
                  AND NOT EXISTS(SELECT 1 FROM fitness.meals m JOIN fitness.food_log_items l ON l.meal_id=m.id
                    WHERE m.student_id=s.student_id AND m.log_date=s.log_date AND NOT EXISTS(
                      SELECT 1 FROM fitness.food_analysis_draft_items i JOIN fitness.food_analysis_requests r ON r.id=i.request_id
                      WHERE i.confirmed_food_log_item_id=l.id AND r.student_id=? AND r.input_type='TEXT' AND r.command_key IS NOT NULL))
                """, userId, userId);
        jdbc.update("""
                UPDATE fitness.meals m SET consumed_at=NULL,meal_type=NULL,log_date=NULL,timezone=NULL,title=NULL,notes=NULL,
                  erased_at=COALESCE(m.erased_at,clock_timestamp()),erasure_request_id=?
                FROM fitness.food_analysis_requests a WHERE a.student_id=? AND a.input_type='TEXT' AND a.command_key IS NOT NULL
                  AND a.meal_id=m.id AND m.student_id=a.student_id AND m.created_by=a.student_id
                  AND m.erased_at IS NULL
                  AND NOT EXISTS(SELECT 1 FROM fitness.food_log_items l WHERE l.meal_id=m.id AND NOT EXISTS(
                    SELECT 1 FROM fitness.food_analysis_draft_items i JOIN fitness.food_analysis_requests r ON r.id=i.request_id
                    WHERE i.confirmed_food_log_item_id=l.id AND r.student_id=? AND r.input_type='TEXT' AND r.command_key IS NOT NULL))
                """, requestId, userId, userId);
        jdbc.update("""
                UPDATE fitness.food_analysis_previews p SET selection_json='[]'::jsonb,nutrients_json='{}'::jsonb,
                  command_key='erased-'||p.id::text,payload_hash=repeat('0',64)
                FROM fitness.food_analysis_requests a WHERE a.id=p.request_id AND a.student_id=?
                  AND a.input_type='TEXT' AND a.command_key IS NOT NULL
                """, userId);
        jdbc.update("""
                UPDATE fitness.food_analysis_candidates c SET detected_name='ERASED',matched_food_id=NULL,
                  estimated_quantity=NULL,estimated_unit_id=NULL,confidence_score=NULL,bounding_box=NULL
                FROM fitness.food_analysis_requests a WHERE a.id=c.request_id AND a.student_id=?
                  AND a.input_type='TEXT' AND a.command_key IS NOT NULL
                """, userId);
        jdbc.update("""
                UPDATE fitness.food_analysis_draft_items i SET detected_name=NULL,candidate_names='[]'::jsonb,
                  estimated_quantity=NULL,estimated_unit_text=NULL,confidence=NULL,missing_data='[]'::jsonb,limitations='[]'::jsonb,
                  selected_food_id=NULL,selected_revision_id=NULL,quantity=NULL,quantity_basis=NULL,portion_id=NULL
                FROM fitness.food_analysis_requests a WHERE a.id=i.request_id AND a.student_id=?
                  AND a.input_type='TEXT' AND a.command_key IS NOT NULL
                """, userId);
        jdbc.update("""
                UPDATE fitness.food_analysis_requests SET input_text='ERASED',command_key='erased-'||id::text,
                  request_fingerprint=repeat('0',64),log_date=NULL,timezone=NULL,consumed_at=NULL,meal_type=NULL,expires_at=NULL,
                  status=CASE WHEN status IN ('PENDING','PROCESSING','READY_FOR_CONFIRMATION') THEN 'CANCELLED' ELSE status END,
                  erased_at=COALESCE(erased_at,clock_timestamp()),erasure_request_id=?,failure_reason=NULL
                WHERE student_id=? AND input_type='TEXT' AND command_key IS NOT NULL AND erased_at IS NULL
                """, requestId, userId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void minimizeAuthoredMealText(UUID userId) {
        // A TEXT analysis request is Student-owned and its input is supplied
        // by that Student. The legacy CHECK requires non-NULL input_text, so
        // retain a fixed non-identifying sentinel rather than violating it.
        // IMAGE requests and AI candidate text require separate media and
        // provenance review.
        jdbc.update("""
                UPDATE fitness.food_analysis_requests SET input_text='ERASED'
                WHERE student_id=? AND input_type='TEXT'
                  AND input_text IS DISTINCT FROM 'ERASED'
                """, userId);
        // The meal has an explicit creator; item snapshots and analysis media do not
        // have equivalent per-field/object proof and must not be guessed from this FK.
        jdbc.update("""
                UPDATE fitness.meals SET title=NULL,notes=NULL
                WHERE created_by=? AND (title IS NOT NULL OR notes IS NOT NULL)
                """, userId);
        // V16 forbids changing locked versions. Their authored summaries need an
        // evidenced redaction path and remain unresolved rather than blocking
        // all other partial cleanup in this transaction.
        jdbc.update("""
                UPDATE fitness.nutrition_goal_versions SET change_summary=NULL
                WHERE created_by=? AND locked_at IS NULL AND change_summary IS NOT NULL
                """, userId);
        // V39 makes these catalog entries explicitly Student-owned and
        // USER_REPORTED. Keep their IDs for referenced food-log history, but
        // remove the current private name, serving label and nutrient values.
        // Food-item snapshots and append-only correction JSON need separate
        // provenance/retention review; this is not a Nutrition attestation.
        jdbc.update("""
                UPDATE fitness.student_private_foods
                SET name='ERASED',serving_label='ERASED',
                    energy_kcal_per_serving=NULL,protein_g_per_serving=NULL,
                    carbohydrate_g_per_serving=NULL,fat_g_per_serving=NULL,
                    archived_at=COALESCE(archived_at,clock_timestamp()),
                    updated_at=clock_timestamp()
                WHERE student_id=? AND (name<>'ERASED' OR serving_label<>'ERASED'
                    OR energy_kcal_per_serving IS NOT NULL OR protein_g_per_serving IS NOT NULL
                    OR carbohydrate_g_per_serving IS NOT NULL OR fat_g_per_serving IS NOT NULL
                    OR archived_at IS NULL)
                """, userId);
    }
}
