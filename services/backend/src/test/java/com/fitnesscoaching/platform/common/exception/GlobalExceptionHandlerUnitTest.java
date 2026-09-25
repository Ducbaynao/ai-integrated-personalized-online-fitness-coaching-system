package com.fitnesscoaching.platform.common.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerUnitTest {

    private GlobalExceptionHandler handler;
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-09-21T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(fixedClock);
    }

    @Test
    @DisplayName("DataIntegrityViolation with trainer_profiles_pkey constraint maps to TRAINER_PROFILE_ALREADY_EXISTS")
    void handleDataIntegrityViolation_trainerProfilesPkey_mapsToTrainerProfileAlreadyExists() {
        SQLException sqlEx = new SQLException("duplicate key value violates unique constraint \"trainer_profiles_pkey\"", "23505");
        ConstraintViolationException cve = new ConstraintViolationException(
                "could not execute statement", sqlEx, "trainer_profiles_pkey"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Error executing statement", cve);

        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrityViolation(dive);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("TRAINER_PROFILE_ALREADY_EXISTS");
        assertThat(response.getBody().message()).contains("already exists");
    }

    @Test
    @DisplayName("DataIntegrityViolation with trainer_profiles_public_slug_key constraint maps to TRAINER_SLUG_ALREADY_EXISTS")
    void handleDataIntegrityViolation_trainerProfilesSlugKey_mapsToTrainerSlugAlreadyExists() {
        SQLException sqlEx = new SQLException("duplicate key value violates unique constraint \"trainer_profiles_public_slug_key\"", "23505");
        ConstraintViolationException cve = new ConstraintViolationException(
                "could not execute statement", sqlEx, "trainer_profiles_public_slug_key"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Error executing statement", cve);

        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrityViolation(dive);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("TRAINER_SLUG_ALREADY_EXISTS");
        assertThat(response.getBody().message()).contains("Public slug is already in use");
    }

    @Test
    @DisplayName("DataIntegrityViolation with foreign key on trainer_profiles table maps to DATA_INTEGRITY_VIOLATION, NOT TRAINER_PROFILE_ALREADY_EXISTS")
    void handleDataIntegrityViolation_otherConstraintOnTrainerProfiles_mapsToDataIntegrityViolation() {
        // A foreign key violation on trainer_profiles (e.g. fk_trainer_profiles_user_id)
        SQLException sqlEx = new SQLException(
                "insert or update on table \"trainer_profiles\" violates foreign key constraint \"fk_trainer_profiles_user_id\"",
                "23503"
        );
        ConstraintViolationException cve = new ConstraintViolationException(
                "could not execute statement", sqlEx, "fk_trainer_profiles_user_id"
        );
        DataIntegrityViolationException dive = new DataIntegrityViolationException(
                "could not execute statement [ERROR: insert or update on table \"trainer_profiles\" violates foreign key constraint \"fk_trainer_profiles_user_id\"]",
                cve
        );

        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrityViolation(dive);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        // Must NOT be mapped to TRAINER_PROFILE_ALREADY_EXISTS
        assertThat(response.getBody().errorCode()).isNotEqualTo("TRAINER_PROFILE_ALREADY_EXISTS");
        assertThat(response.getBody().errorCode()).isNotEqualTo("TRAINER_SLUG_ALREADY_EXISTS");
        assertThat(response.getBody().errorCode()).isEqualTo("DATA_INTEGRITY_VIOLATION");
    }

    @Test
    @DisplayName("DataIntegrityViolation with check constraint on trainer_profiles table maps to DATA_INTEGRITY_VIOLATION")
    void handleDataIntegrityViolation_checkConstraintOnTrainerProfiles_mapsToDataIntegrityViolation() {
        // Message contains "trainer_profiles" but refers to check constraint, not pkey or slug_key
        DataIntegrityViolationException dive = new DataIntegrityViolationException(
                "ERROR: new row for relation \"trainer_profiles\" violates check constraint \"trainer_profiles_years_experience_check\""
        );

        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrityViolation(dive);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isNotEqualTo("TRAINER_PROFILE_ALREADY_EXISTS");
        assertThat(response.getBody().errorCode()).isEqualTo("DATA_INTEGRITY_VIOLATION");
    }

    @Test
    @DisplayName("GoalLifecycleConflictException maps to 409 GOAL_LIFECYCLE_CONFLICT")
    void handleGoalLifecycleConflictException_mapsTo409GoalLifecycleConflict() {
        GoalLifecycleConflictException ex = new GoalLifecycleConflictException("Goal is not in active state");

        ResponseEntity<ErrorResponse> response = handler.handleGoalLifecycleConflict(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("GOAL_LIFECYCLE_CONFLICT");
        assertThat(response.getBody().message()).isEqualTo("Goal is not in active state");
    }
}
