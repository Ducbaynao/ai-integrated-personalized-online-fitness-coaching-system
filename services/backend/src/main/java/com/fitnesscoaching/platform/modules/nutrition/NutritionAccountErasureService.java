package com.fitnesscoaching.platform.modules.nutrition;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class NutritionAccountErasureService implements NutritionAccountErasure {
    private final JdbcTemplate jdbc;

    public NutritionAccountErasureService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
