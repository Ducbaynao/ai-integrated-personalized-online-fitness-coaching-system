package com.fitnesscoaching.platform.modules.student;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class StudentProfileAccountErasureService implements StudentProfileAccountErasure {
    private final JdbcTemplate jdbc;

    public StudentProfileAccountErasureService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void minimizeCurrentProfile(UUID userId) {
        // The profile is keyed by the account itself. Keep the row and its
        // onboarding chronology for FK-linked history; free-text limitations
        // and availability require a separate provenance/retention review.
        jdbc.update("""
                UPDATE fitness.student_profiles SET date_of_birth=NULL,gender=NULL,
                    training_experience_level=NULL,training_experience_months=NULL,
                    available_days_per_week=NULL,preferred_session_minutes=NULL,
                    updated_at=clock_timestamp()
                WHERE user_id=? AND (date_of_birth IS NOT NULL OR gender IS NOT NULL
                    OR training_experience_level IS NOT NULL OR training_experience_months IS NOT NULL
                    OR available_days_per_week IS NOT NULL OR preferred_session_minutes IS NOT NULL)
                """, userId);
    }
}
