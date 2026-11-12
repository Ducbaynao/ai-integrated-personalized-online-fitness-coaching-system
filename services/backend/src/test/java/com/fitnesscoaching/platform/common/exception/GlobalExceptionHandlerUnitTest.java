package com.fitnesscoaching.platform.common.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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
        org.hibernate.exception.ConstraintViolationException cve = new org.hibernate.exception.ConstraintViolationException(
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
        org.hibernate.exception.ConstraintViolationException cve = new org.hibernate.exception.ConstraintViolationException(
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
        org.hibernate.exception.ConstraintViolationException cve = new org.hibernate.exception.ConstraintViolationException(
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

    @Test
    @DisplayName("MissingServletRequestParameterException maps to 400 VALIDATION_FAILED with field error")
    void handleMissingServletRequestParameter_mapsTo400ValidationFailed() {
        MissingServletRequestParameterException ex = new MissingServletRequestParameterException("scope", "String");

        ResponseEntity<ErrorResponse> response = handler.handleMissingServletRequestParameter(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().message()).contains("Parameter 'scope' is required");
        assertThat(response.getBody().fieldErrors()).hasSize(1);
        assertThat(response.getBody().fieldErrors().get(0).field()).isEqualTo("scope");
        assertThat(response.getBody().fieldErrors().get(0).code()).isEqualTo("NotNull");
    }

    @Test
    @DisplayName("MissingServletRequestPartException maps to 400 VALIDATION_FAILED with field error")
    void handleMissingServletRequestPart_mapsTo400ValidationFailed() {
        MissingServletRequestPartException ex = new MissingServletRequestPartException("file");

        ResponseEntity<ErrorResponse> response = handler.handleMissingServletRequestPart(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().message()).contains("Required part 'file' is not present");
        assertThat(response.getBody().fieldErrors()).hasSize(1);
        assertThat(response.getBody().fieldErrors().get(0).field()).isEqualTo("file");
    }

    @Test
    @DisplayName("MissingRequestHeaderException maps to 400 VALIDATION_FAILED with field error")
    void handleMissingRequestHeader_mapsTo400ValidationFailed() {
        MissingRequestHeaderException ex = new MissingRequestHeaderException("X-Correlation-Id", null);

        ResponseEntity<ErrorResponse> response = handler.handleMissingRequestHeader(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().fieldErrors()).hasSize(1);
        assertThat(response.getBody().fieldErrors().get(0).field()).isEqualTo("X-Correlation-Id");
    }

    @Test
    @DisplayName("ConstraintViolationException maps to 400 VALIDATION_FAILED")
    void handleConstraintViolationException_mapsTo400ValidationFailed() {
        ConstraintViolation<?> violation = Mockito.mock(ConstraintViolation.class);
        jakarta.validation.Path path = Mockito.mock(jakarta.validation.Path.class);
        Mockito.when(path.toString()).thenReturn("page");
        Mockito.when(violation.getPropertyPath()).thenReturn(path);
        Mockito.when(violation.getMessage()).thenReturn("must be greater than or equal to 0");

        ConstraintViolationException ex = new ConstraintViolationException("Invalid parameter", Set.of(violation));

        ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().fieldErrors()).hasSize(1);
        assertThat(response.getBody().fieldErrors().get(0).field()).isEqualTo("page");
        assertThat(response.getBody().fieldErrors().get(0).message()).contains("greater than or equal to 0");
    }

    @Test
    @DisplayName("DateTimeException maps to 400 VALIDATION_FAILED")
    void handleDateTimeException_mapsTo400ValidationFailed() {
        DateTimeException ex = new DateTimeParseException("Text 'invalid-date' could not be parsed", "invalid-date", 0);

        ResponseEntity<ErrorResponse> response = handler.handleDateTimeException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().message()).contains("Invalid date, time or timezone format");
    }

    @Test
    @DisplayName("IllegalArgumentException maps to 400 VALIDATION_FAILED")
    void handleIllegalArgumentException_mapsTo400ValidationFailed() {
        IllegalArgumentException ex = new IllegalArgumentException("Unknown status code: INVALID");

        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().message()).isEqualTo("Unknown status code: INVALID");
    }

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException maps to 405 METHOD_NOT_ALLOWED")
    void handleHttpRequestMethodNotSupported_mapsTo405() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("POST");

        ResponseEntity<ErrorResponse> response = handler.handleHttpRequestMethodNotSupported(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getBody().message()).contains("HTTP method 'POST' is not supported");
    }

    @Test
    @DisplayName("HttpMediaTypeNotSupportedException maps to 415 UNSUPPORTED_MEDIA_TYPE")
    void handleHttpMediaTypeNotSupported_mapsTo415() {
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<ErrorResponse> response = handler.handleHttpMediaTypeNotSupported(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    @DisplayName("HttpMediaTypeNotAcceptableException maps to 406 NOT_ACCEPTABLE")
    void handleHttpMediaTypeNotAcceptable_mapsTo406() {
        HttpMediaTypeNotAcceptableException ex = new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<ErrorResponse> response = handler.handleHttpMediaTypeNotAcceptable(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("NOT_ACCEPTABLE");
    }

    @Test
    @DisplayName("NoResourceFoundException maps to 404 NOT_FOUND")
    void handleNoResourceFoundException_mapsTo404() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "Resource not found", "api/v1/unknown");

        ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().message()).contains("api/v1/unknown");
    }

    @Test
    @DisplayName("EmptyResultDataAccessException maps to 404 NOT_FOUND")
    void handleEmptyResultDataAccessException_mapsTo404() {
        EmptyResultDataAccessException ex = new EmptyResultDataAccessException("No record found", 1);

        ResponseEntity<ErrorResponse> response = handler.handleEmptyResultDataAccess(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("NOT_FOUND");
    }
}
