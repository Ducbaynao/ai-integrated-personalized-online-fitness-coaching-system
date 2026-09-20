package com.fitnesscoaching.platform.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Explicit development-only CORS configuration.
 * Active ONLY under the 'dev' profile.
 * Production/default profiles do NOT load this bean, preventing unintended CORS access.
 */
@Configuration
@Profile("dev")
public class DevCorsConfig {

    private final List<String> allowedOrigins;

    public DevCorsConfig(
            @Value("${app.cors.allowed-origins:http://localhost:8081,http://127.0.0.1:8081}")
            String[] allowedOrigins
    ) {
        this.allowedOrigins = Arrays.stream(allowedOrigins)
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Strict allowlist: never use wildcard '*' when credentials or Authorization headers are used
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Request-ID"));
        config.setExposedHeaders(List.of("Location", "X-Request-ID"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
