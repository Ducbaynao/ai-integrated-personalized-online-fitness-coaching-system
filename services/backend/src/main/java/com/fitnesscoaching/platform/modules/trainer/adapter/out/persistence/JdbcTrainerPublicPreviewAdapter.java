package com.fitnesscoaching.platform.modules.trainer.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.trainer.application.model.TrainerPublicPreview;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerPublicPreviewQuery;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JdbcTrainerPublicPreviewAdapter implements TrainerPublicPreviewQuery {
    private final JdbcTemplate jdbcTemplate;

    public JdbcTrainerPublicPreviewAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TrainerPublicPreview> findPublicPreview(UUID trainerId) {
        if (trainerId == null) {
            return Optional.empty();
        }
        List<TrainerRow> rows = jdbcTemplate.query("""
                SELECT user_id, verification_status, years_experience, is_accepting_students
                FROM fitness.trainer_profiles
                WHERE user_id = ? AND is_active = true
                """, (rs, ignored) -> new TrainerRow(
                rs.getObject("user_id", UUID.class),
                TrainerVerificationStatus.valueOf(rs.getString("verification_status")),
                rs.getBigDecimal("years_experience"),
                rs.getBoolean("is_accepting_students")
        ), trainerId);

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        TrainerRow row = rows.get(0);
        List<String> specialties = jdbcTemplate.query("""
                SELECT ts.name
                FROM fitness.trainer_specialty_assignments tsa
                JOIN fitness.trainer_specialties ts ON ts.id = tsa.specialty_id
                WHERE tsa.trainer_id = ?
                ORDER BY tsa.is_primary DESC, ts.name ASC
                """, (rs, ignored) -> rs.getString("name"), trainerId);

        return Optional.of(new TrainerPublicPreview(
                row.trainerId(),
                row.verificationStatus(),
                specialties,
                row.yearsExperience(),
                row.isAcceptingStudents()
        ));
    }

    private record TrainerRow(
            UUID trainerId,
            TrainerVerificationStatus verificationStatus,
            BigDecimal yearsExperience,
            boolean isAcceptingStudents
    ) {}
}
