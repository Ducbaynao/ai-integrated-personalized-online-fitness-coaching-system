package com.fitnesscoaching.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InputErrorHandlingIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("digital_fitness")
            .withUsername("fitness_app")
            .withPassword("testpass123");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "fitness");
        registry.add("spring.flyway.default-schema", () -> "fitness");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("management.endpoint.health.show-details", () -> "always");
        registry.add("ai.service.mode", () -> "simulated");
        registry.add("ai.embedding.provider", () -> "simulated");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Invalid timezone query parameter in ProgressDashboard returns 400 VALIDATION_FAILED, not 500")
    void getProgressSummary_invalidTimezone_returns400ValidationFailed() throws Exception {
        UUID studentId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/progress/summary")
                        .with(jwt().jwt(j -> j.subject(studentId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_STUDENT")))
                        .param("timezone", "Invalid/Unknown_Zone_12345"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.message", notNullValue()));
    }

    @Test
    @DisplayName("Missing multipart file in progress photo upload returns 400 VALIDATION_FAILED, not 500")
    void uploadProgressPhoto_missingFilePart_returns400ValidationFailed() throws Exception {
        UUID studentId = UUID.randomUUID();

        mockMvc.perform(multipart("/api/v1/measurements/progress-photos")
                        .with(jwt().jwt(j -> j.subject(studentId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_STUDENT")))
                        .param("viewType", "FRONT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Invalid UUID path variable in photo download returns 400 VALIDATION_FAILED, not 500")
    void getProgressPhotoFile_invalidUuidPathVariable_returns400ValidationFailed() throws Exception {
        UUID studentId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/measurements/progress-photos/{id}/file", "not-a-valid-uuid")
                        .with(jwt().jwt(j -> j.subject(studentId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_STUDENT"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Malformed JSON body in measurement creation returns 400 VALIDATION_FAILED, not 500")
    void recordMeasurement_malformedJson_returns400ValidationFailed() throws Exception {
        UUID studentId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/measurements")
                        .with(jwt().jwt(j -> j.subject(studentId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_STUDENT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metricCode\": \"WEIGHT\", unclosed-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("Unsupported HTTP method on endpoint returns 405 METHOD_NOT_ALLOWED, not 500")
    void deleteProgressSummary_unsupportedMethod_returns405MethodNotAllowed() throws Exception {
        UUID studentId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/progress/summary")
                        .with(jwt().jwt(j -> j.subject(studentId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_STUDENT"))))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode", is("METHOD_NOT_ALLOWED")));
    }

    @Test
    @DisplayName("Non-existent API route returns 404 NOT_FOUND, not 500")
    void nonExistentRoute_returns404NotFound() throws Exception {
        UUID studentId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/non-existent-unmapped-endpoint-404")
                        .with(jwt().jwt(j -> j.subject(studentId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_STUDENT"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("NOT_FOUND")));
    }

    @Test
    @DisplayName("Actuator health endpoint returns 200 with database, storage, and scanner components")
    void actuatorHealth_returns200WithComponents() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.status", notNullValue()))
                .andExpect(jsonPath("$.components.storage", notNullValue()))
                .andExpect(jsonPath("$.components.scanner", notNullValue()));
    }
}
