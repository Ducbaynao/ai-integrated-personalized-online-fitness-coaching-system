package com.fitnesscoaching.platform.modules.trainer;

import com.fitnesscoaching.platform.modules.user.application.port.out.TrainerProfileAccountErasure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TrainerProfileAccountErasureService implements TrainerProfileAccountErasure {
    private final JdbcTemplate jdbc;

    public TrainerProfileAccountErasureService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void minimizeCurrentProfile(UUID userId) {
        // Do not erase qualification/application/certificate history here.
        jdbc.update("""
                UPDATE fitness.trainer_profiles SET public_slug=NULL,bio=NULL,years_experience=NULL,
                    is_accepting_students=false,updated_at=clock_timestamp()
                WHERE user_id=? AND (public_slug IS NOT NULL OR bio IS NOT NULL
                    OR years_experience IS NOT NULL OR is_accepting_students)
                """, userId);
    }
}
