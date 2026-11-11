package com.fitnesscoaching.platform.common.config;

import com.fitnesscoaching.platform.common.security.RestAccessDeniedHandler;
import com.fitnesscoaching.platform.common.security.RestAuthenticationEntryPoint;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(UserRoleQuery userRoleQuery) {
        JwtGrantedAuthoritiesConverter fallbackConverter = new JwtGrantedAuthoritiesConverter();
        fallbackConverter.setAuthoritiesClaimName("roles");
        fallbackConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            try {
                UUID userId = UUID.fromString(jwt.getSubject());
                List<String> activeRoles = userRoleQuery.getActiveRoles(userId);
                return activeRoles.stream()
                        .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                        .toList();
            } catch (Exception ignored) {
                Collection<GrantedAuthority> fallback = fallbackConverter.convert(jwt);
                return fallback != null ? fallback : List.of();
            }
        });
        return converter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            JwtAuthenticationConverter jwtAuthenticationConverter,
            Optional<CorsConfigurationSource> corsConfigurationSource
    ) throws Exception {
        if (corsConfigurationSource.isPresent()) {
            http.cors(cors -> cors.configurationSource(corsConfigurationSource.get()));
        } else {
            http.cors(AbstractHttpConfigurer::disable);
        }

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/registrations").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/email-verifications/confirmations").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/sessions").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/token-refreshes").permitAll()
                        // Only the high-entropy request-specific status capability survives bearer cutoff.
                        .requestMatchers(HttpMethod.GET, "/api/v1/account-erasure-requests/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated()
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .build();
    }
}
