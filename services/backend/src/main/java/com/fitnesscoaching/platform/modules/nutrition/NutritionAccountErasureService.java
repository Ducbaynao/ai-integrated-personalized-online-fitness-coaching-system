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
        // The meal has an explicit creator; item snapshots and analysis media do not
        // have equivalent per-field/object proof and must not be guessed from this FK.
        jdbc.update("""
                UPDATE fitness.meals SET title=NULL,notes=NULL
                WHERE created_by=? AND (title IS NOT NULL OR notes IS NOT NULL)
                """, userId);
    }
}
