package com.fitnesscoaching.platform.modules.student.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.student.application.port.out.StudentProfilePort;
import com.fitnesscoaching.platform.modules.student.domain.Gender;
import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;
import com.fitnesscoaching.platform.modules.student.domain.TrainingExperienceLevel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class StudentProfilePersistenceAdapter implements StudentProfilePort {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<StudentProfile> rowMapper = (rs, rowNum) -> {
        UUID userId = (UUID) rs.getObject("user_id");
        Date dobDate = rs.getDate("date_of_birth");
        LocalDate dateOfBirth = dobDate != null ? dobDate.toLocalDate() : null;

        String genderStr = rs.getString("gender");
        Gender gender = genderStr != null ? Gender.valueOf(genderStr) : null;

        String levelStr = rs.getString("training_experience_level");
        TrainingExperienceLevel level = levelStr != null ? TrainingExperienceLevel.valueOf(levelStr) : null;

        BigDecimal months = rs.getBigDecimal("training_experience_months");
        Integer days = rs.getObject("available_days_per_week", Integer.class);
        Integer minutes = rs.getObject("preferred_session_minutes", Integer.class);

        Timestamp compTs = rs.getTimestamp("onboarding_completed_at");
        Instant onboardingCompletedAt = compTs != null ? compTs.toInstant() : null;

        Timestamp createdTs = rs.getTimestamp("created_at");
        Instant createdAt = createdTs != null ? createdTs.toInstant() : null;

        Timestamp updatedTs = rs.getTimestamp("updated_at");
        Instant updatedAt = updatedTs != null ? updatedTs.toInstant() : null;

        return new StudentProfile(
                userId,
                dateOfBirth,
                gender,
                level,
                months,
                days,
                minutes,
                onboardingCompletedAt,
                createdAt,
                updatedAt
        );
    };

    public StudentProfilePersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<StudentProfile> findByUserId(UUID userId) {
        String sql = """
                SELECT user_id, date_of_birth, gender, training_experience_level,
                       training_experience_months, available_days_per_week, preferred_session_minutes,
                       onboarding_completed_at, created_at, updated_at
                FROM fitness.student_profiles
                WHERE user_id = ?
                """;

        List<StudentProfile> results = jdbcTemplate.query(sql, rowMapper, userId);
        if (results.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(results.get(0));
    }

    @Override
    public boolean existsByUserId(UUID userId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM fitness.student_profiles WHERE user_id = ?)",
                Boolean.class,
                userId
        );
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public StudentProfile save(StudentProfile profile) {
        String sql = """
                INSERT INTO fitness.student_profiles (
                    user_id, date_of_birth, gender, training_experience_level,
                    training_experience_months, available_days_per_week, preferred_session_minutes,
                    onboarding_completed_at, created_at, updated_at
                ) VALUES (
                    ?, ?, ?::fitness.gender_code, ?,
                    ?, ?, ?,
                    ?, ?, ?
                )
                """;

        jdbcTemplate.update(
                sql,
                profile.userId(),
                profile.dateOfBirth() != null ? Date.valueOf(profile.dateOfBirth()) : null,
                profile.gender() != null ? profile.gender().name() : null,
                profile.trainingExperienceLevel() != null ? profile.trainingExperienceLevel().name() : null,
                profile.trainingExperienceMonths(),
                profile.availableDaysPerWeek(),
                profile.preferredSessionMinutes(),
                profile.onboardingCompletedAt() != null ? Timestamp.from(profile.onboardingCompletedAt()) : null,
                Timestamp.from(profile.createdAt()),
                Timestamp.from(profile.updatedAt())
        );

        return profile;
    }

    @Override
    public StudentProfile update(StudentProfile profile) {
        String sql = """
                UPDATE fitness.student_profiles SET
                    date_of_birth = ?,
                    gender = ?::fitness.gender_code,
                    training_experience_level = ?,
                    training_experience_months = ?,
                    available_days_per_week = ?,
                    preferred_session_minutes = ?,
                    onboarding_completed_at = ?,
                    updated_at = ?
                WHERE user_id = ?
                """;

        jdbcTemplate.update(
                sql,
                profile.dateOfBirth() != null ? Date.valueOf(profile.dateOfBirth()) : null,
                profile.gender() != null ? profile.gender().name() : null,
                profile.trainingExperienceLevel() != null ? profile.trainingExperienceLevel().name() : null,
                profile.trainingExperienceMonths(),
                profile.availableDaysPerWeek(),
                profile.preferredSessionMinutes(),
                profile.onboardingCompletedAt() != null ? Timestamp.from(profile.onboardingCompletedAt()) : null,
                Timestamp.from(profile.updatedAt()),
                profile.userId()
        );

        return profile;
    }
}
