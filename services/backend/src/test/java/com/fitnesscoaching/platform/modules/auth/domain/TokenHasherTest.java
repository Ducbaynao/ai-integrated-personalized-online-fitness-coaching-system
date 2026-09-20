package com.fitnesscoaching.platform.modules.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenHasherTest {

    @Test
    @DisplayName("Hashing produces a deterministic 64-character hex string")
    void hashingIsDeterministicAnd64HexChars() {
        String token = "sample-verification-token-value-12345";
        String hash1 = TokenHasher.sha256Hex(token);
        String hash2 = TokenHasher.sha256Hex(token);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);
        assertThat(hash1).matches("^[a-f0-9]{64}$");
    }

    @Test
    @DisplayName("Different tokens produce different hashes")
    void differentTokensProduceDifferentHashes() {
        String hashA = TokenHasher.sha256Hex("token-value-a-1234567890");
        String hashB = TokenHasher.sha256Hex("token-value-b-1234567890");

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    @DisplayName("Null token throws IllegalArgumentException")
    void nullTokenThrows() {
        assertThatThrownBy(() -> TokenHasher.sha256Hex(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
