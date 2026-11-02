package com.fitnesscoaching.platform.modules.workout;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WorkoutAccountErasureService implements WorkoutAccountErasure {
    private final JdbcTemplate jdbc;

    public WorkoutAccountErasureService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void minimizeAuthoredVersionSummaries(UUID userId) {
        // Version summary is immutable, and created_by is the author. Other plan/session
        // text has no reliable per-field author and remains pending owner review.
        jdbc.update("""
                UPDATE fitness.workout_plan_versions SET change_summary=NULL
                WHERE created_by=? AND change_summary IS NOT NULL
                """, userId);
    }
}
