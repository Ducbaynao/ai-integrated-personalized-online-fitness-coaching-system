package com.fitnesscoaching.platform.modules.coaching.adapter.in.web;

import com.fitnesscoaching.platform.common.config.ClockConfig;
import com.fitnesscoaching.platform.common.config.JacksonConfig;
import com.fitnesscoaching.platform.common.config.SecurityConfig;
import com.fitnesscoaching.platform.common.exception.GlobalExceptionHandler;
import com.fitnesscoaching.platform.common.security.RestAccessDeniedHandler;
import com.fitnesscoaching.platform.common.security.RestAuthenticationEntryPoint;
import com.fitnesscoaching.platform.common.web.RequestIdFilter;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingMobileReadUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserDirectoryQuery;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CoachingController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        ClockConfig.class, GlobalExceptionHandler.class, CoachingErrorAdvice.class,
        RequestIdFilter.class, JacksonConfig.class})
class CoachingErrorAdviceTest {
    private static final String REQUEST_ID = "coaching-integrity-test";
    private static final String SQL_DETAIL = "SECRET SQL detail uq_coaching_current_student";

    @Autowired MockMvc mvc;
    @MockitoBean CoachingLifecycleUseCase lifecycle;
    @MockitoBean CoachingMobileReadUseCase mobileRead;
    @MockitoBean CoachingSharingUseCase sharing;
    @MockitoBean UserDirectoryQuery users;
    @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    @Test
    void unknownConstraintUsesCommonServerEnvelope() throws Exception {
        assertEnvelope(databaseError("23505", "unknown_unique", SQL_DETAIL),
                500, "INTERNAL_SERVER_ERROR", "An unexpected server error occurred.");
    }

    @Test
    void uniqueViolationWithoutConstraintMetadataUsesCommonServerEnvelope() throws Exception {
        assertEnvelope(databaseError("23505", null, SQL_DETAIL),
                500, "INTERNAL_SERVER_ERROR", "An unexpected server error occurred.");
    }

    @Test
    void familiarConstraintInMessageWithWrongSqlStateDoesNotBecomeConflict() throws Exception {
        assertEnvelope(databaseError("23503", "uq_coaching_current_student", SQL_DETAIL),
                500, "INTERNAL_SERVER_ERROR", "An unexpected server error occurred.");
    }

    @Test
    void allowlistedSqlStateAndConstraintUsesStableConflict() throws Exception {
        assertEnvelope(databaseError("23505", "uq_coaching_current_student", SQL_DETAIL),
                409, "COACHING_STUDENT_ALREADY_ASSIGNED", "Coaching operation conflicts with current state");
    }

    @Test
    void expirySqlStateUsesStableConflictWithoutConstraint() throws Exception {
        assertEnvelope(databaseError("PZ001", null, SQL_DETAIL),
                409, "COACHING_PERIOD_CONFLICT", "Coaching operation conflicts with current state");
    }

    @Test
    void exclusionConstraintRequiresMatchingSqlState() throws Exception {
        assertEnvelope(databaseError("23P01", "coaching_period_no_overlap", SQL_DETAIL),
                409, "COACHING_PERIOD_CONFLICT", "Coaching operation conflicts with current state");
    }

    @Test
    void sharingOverlapUsesStableConflictWithoutLeakingDatabaseDetail() throws Exception {
        assertEnvelope(databaseError("23P01", "data_sharing_permission_no_overlap", SQL_DETAIL),
                409, "DATA_SHARING_PERMISSION_CONFLICT", "Coaching operation conflicts with current state");
    }

    private void assertEnvelope(DataIntegrityViolationException error, int expectedStatus,
                                String expectedCode, String expectedMessage) throws Exception {
        when(lifecycle.detail(any(UUID.class), any(UUID.class))).thenThrow(error);
        MvcResult result = mvc.perform(get("/api/v1/coaching/relationships/{id}", UUID.randomUUID())
                        .with(jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString())))
                        .header("X-Request-ID", REQUEST_ID))
                .andExpect(status().is(expectedStatus))
                .andExpect(header().string("X-Request-ID", REQUEST_ID))
                .andExpect(jsonPath("$.errorCode", is(expectedCode)))
                .andExpect(jsonPath("$.message", is(expectedMessage)))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.requestId", is(REQUEST_ID)))
                .andExpect(jsonPath("$.fieldErrors", empty()))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(SQL_DETAIL, "unknown_unique");
    }

    private static DataIntegrityViolationException databaseError(
            String sqlState, String constraint, String message) {
        PSQLException sql = mock(PSQLException.class);
        ServerErrorMessage server = mock(ServerErrorMessage.class);
        when(sql.getSQLState()).thenReturn(sqlState);
        when(sql.getServerErrorMessage()).thenReturn(server);
        if (constraint != null) {
            when(server.getConstraint()).thenReturn(constraint);
        }
        when(sql.getMessage()).thenReturn(message);
        return new DataIntegrityViolationException("outer " + message, sql);
    }
}
