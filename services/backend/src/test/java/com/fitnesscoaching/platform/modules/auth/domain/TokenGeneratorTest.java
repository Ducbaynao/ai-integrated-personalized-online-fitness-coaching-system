package com.fitnesscoaching.platform.modules.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TokenGeneratorTest {

    @Test
    @DisplayName("Generated token is URL-safe, non-empty, and at least 20 characters")
    void tokenMeetsFormatRequirements() {
        String token = TokenGenerator.generateSecureToken();

        assertThat(token).isNotBlank();
        assertThat(token.length()).isGreaterThanOrEqualTo(20);
        assertThat(token).matches("^[A-Za-z0-9_-]+$");
    }

    @Test
    @DisplayName("Generated tokens are unique across successive invocations")
    void tokensAreUnique() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            tokens.add(TokenGenerator.generateSecureToken());
        }
        assertThat(tokens).hasSize(100);
    }
}
