package com.fitnesscoaching.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorsIntegrationTest {

    @Nested
    @SpringBootTest(properties = "management.health.mail.enabled=false")
    @AutoConfigureMockMvc
    @ActiveProfiles("dev")
    @DisplayName("Dev Profile CORS behavior")
    class DevProfileTests {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private JavaMailSender mailSender;

        @Test
        @DisplayName("Allowed dev origin (localhost:8081) receives CORS headers on preflight OPTIONS")
        void preflightOptionsAllowedOrigin() throws Exception {
            mockMvc.perform(options("/api/v1/auth/sessions")
                            .header(HttpHeaders.ORIGIN, "http://localhost:8081")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Content-Type,Accept,X-Request-ID"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:8081"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Authorization")))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        }

        @Test
        @DisplayName("Second allowed dev origin (127.0.0.1:8081) receives CORS headers on preflight OPTIONS")
        void preflightOptionsSecondAllowedOrigin() throws Exception {
            mockMvc.perform(options("/api/v1/auth/sessions")
                            .header(HttpHeaders.ORIGIN, "http://127.0.0.1:8081")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://127.0.0.1:8081"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        }

        @Test
        @DisplayName("Allowed dev origin receives CORS headers on actual request")
        void actualRequestAllowedOriginReceivesCorsHeaders() throws Exception {
            mockMvc.perform(get("/actuator/health")
                            .header(HttpHeaders.ORIGIN, "http://localhost:8081"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:8081"))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        }

        @Test
        @DisplayName("Disallowed origin preflight is rejected with 403 and no CORS allow origin header")
        void disallowedOriginPreflightRejected() throws Exception {
            mockMvc.perform(options("/api/v1/auth/sessions")
                            .header(HttpHeaders.ORIGIN, "http://malicious-website.com")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }

        @Test
        @DisplayName("Disallowed origin actual request does not receive CORS allow origin header")
        void disallowedOriginActualRequestNoCorsHeader() throws Exception {
            mockMvc.perform(get("/actuator/health")
                            .header(HttpHeaders.ORIGIN, "http://malicious-website.com"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @DisplayName("Default / Production profile CORS behavior")
    class DefaultProfileTests {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("Default/Production profile does not open CORS to dev origin on preflight")
        void defaultProfileDoesNotEnableCorsOnPreflight() throws Exception {
            mockMvc.perform(options("/api/v1/auth/sessions")
                            .header(HttpHeaders.ORIGIN, "http://localhost:8081")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }

        @Test
        @DisplayName("Default/Production profile does not open CORS on actual request")
        void defaultProfileDoesNotEnableCorsOnActualRequest() throws Exception {
            mockMvc.perform(get("/actuator/health")
                            .header(HttpHeaders.ORIGIN, "http://localhost:8081"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }
}
