package com.fitnesscoaching.platform.modules.trainer.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.trainer.application.port.out.TrainerProfilePort;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerActivityStatus;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerProfile;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class TrainerProfilePersistenceAdapter implements TrainerProfilePort {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<TrainerProfile> rowMapper = (rs, rowNum) -> {
        UUID userId = (UUID) rs.getObject("user_id");
        String publicSlug = rs.getString("public_slug");
        String bio = rs.getString("bio");
        BigDecimal yearsExperience = rs.getBigDecimal("years_experience");

        String verifStr = rs.getString("verification_status");
        TrainerVerificationStatus verificationStatus = verifStr != null
                ? TrainerVerificationStatus.valueOf(verifStr)
                : TrainerVerificationStatus.NOT_SUBMITTED;

        Timestamp verifTs = rs.getTimestamp("verified_at");
        Instant verifiedAt = verifTs != null ? verifTs.toInstant() : null;
        UUID verifiedBy = (UUID) rs.getObject("verified_by");

        boolean isAcceptingStudents = rs.getBoolean("is_accepting_students");
        boolean isActive = rs.getBoolean("is_active");

        String actStr = rs.getString("activity_status");
        TrainerActivityStatus activityStatus = actStr != null
                ? TrainerActivityStatus.valueOf(actStr)
                : TrainerActivityStatus.ACTIVE;

        Timestamp createdTs = rs.getTimestamp("created_at");
        Instant createdAt = createdTs != null ? createdTs.toInstant() : null;

        Timestamp updatedTs = rs.getTimestamp("updated_at");
        Instant updatedAt = updatedTs != null ? updatedTs.toInstant() : null;

        return new TrainerProfile(
                userId,
                publicSlug,
                bio,
                yearsExperience,
                isAcceptingStudents,
                verificationStatus,
                activityStatus,
                verifiedAt,
                verifiedBy,
                isActive,
                createdAt,
                updatedAt
        );
    };

    public TrainerProfilePersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TrainerProfile> findByUserId(UUID userId) {
        String sql = """
                SELECT user_id, public_slug, bio, years_experience,
                       verification_status, verified_at, verified_by,
                       is_accepting_students, is_active, activity_status,
                       created_at, updated_at
                FROM fitness.trainer_profiles
                WHERE user_id = ?
                """;

        List<TrainerProfile> results = jdbcTemplate.query(sql, rowMapper, userId);
        if (results.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(results.get(0));
    }

    @Override
    public boolean existsByUserId(UUID userId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM fitness.trainer_profiles WHERE user_id = ?)",
                Boolean.class,
                userId
        );
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public boolean existsByPublicSlug(String publicSlug) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM fitness.trainer_profiles WHERE public_slug = ?)",
                Boolean.class,
                publicSlug
        );
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public boolean existsByPublicSlugAndUserIdNot(String publicSlug, UUID userId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM fitness.trainer_profiles WHERE public_slug = ? AND user_id != ?)",
                Boolean.class,
                publicSlug,
                userId
        );
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public TrainerProfile save(TrainerProfile profile) {
        String sql = """
                INSERT INTO fitness.trainer_profiles (
                    user_id, public_slug, bio, years_experience,
                    verification_status, verified_at, verified_by,
                    is_accepting_students, is_active, activity_status,
                    created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?,
                    ?::fitness.trainer_verification_state, ?, ?,
                    ?, ?, ?::fitness.trainer_activity_status,
                    ?, ?
                )
                """;

        jdbcTemplate.update(
                sql,
                profile.userId(),
                profile.publicSlug(),
                profile.bio(),
                profile.yearsExperience(),
                profile.verificationStatus().name(),
                profile.verifiedAt() != null ? Timestamp.from(profile.verifiedAt()) : null,
                profile.verifiedBy(),
                profile.isAcceptingStudents(),
                profile.isActive(),
                profile.activityStatus().name(),
                Timestamp.from(profile.createdAt()),
                Timestamp.from(profile.updatedAt())
        );

        return profile;
    }

    @Override
    public TrainerProfile update(TrainerProfile profile) {
        String sql = """
                UPDATE fitness.trainer_profiles SET
                    public_slug = ?,
                    bio = ?,
                    years_experience = ?,
                    is_accepting_students = ?,
                    updated_at = ?
                WHERE user_id = ?
                """;

        jdbcTemplate.update(
                sql,
                profile.publicSlug(),
                profile.bio(),
                profile.yearsExperience(),
                profile.isAcceptingStudents(),
                Timestamp.from(profile.updatedAt()),
                profile.userId()
        );

        return profile;
    }

    @Override
    public void updateVerificationStatus(UUID trainerId, TrainerVerificationStatus status, Instant updatedAt) {
        String sql = """
                UPDATE fitness.trainer_profiles SET
                    verification_status = ?::fitness.trainer_verification_state,
                    updated_at = ?
                WHERE user_id = ?
                """;

        jdbcTemplate.update(
                sql,
                status.name(),
                Timestamp.from(updatedAt),
                trainerId
        );
    }
}
