package com.fitnesscoaching.platform.modules.content.adapter.out.embedding;

import com.fitnesscoaching.platform.modules.content.application.port.out.EmbeddingModelPort;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic unit-vector embedding generator for the baseline space (OpenAI text-embedding-3-small, 1536 dim).
 * Projects text n-grams and vocabulary into 1536 dimensions and applies L2 normalization.
 * Produces mathematically sound cosine distance behaviors for pgvector HNSW search without requiring paid API keys.
 */
@Component
public class DeterministicEmbeddingGenerator implements EmbeddingModelPort {

    private static final int HASH_FEATURES_PER_TOKEN = 6;

    @Override
    public int dimension() {
        return DIMENSION;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String modelCode() {
        return MODEL_CODE;
    }

    @Override
    public String versionLabel() {
        return VERSION_LABEL;
    }

    @Override
    public String modelSpace() {
        return MODEL_SPACE;
    }

    @Override
    public UUID modelVersionId() {
        return MODEL_VERSION_ID;
    }

    @Override
    public float[] generateEmbedding(String text) {
        if (text == null) {
            text = "";
        }

        float[] vector = new float[DIMENSION];
        String normalized = text.toLowerCase().replaceAll("[^\\p{L}\\p{Nd}\\s]", " ").trim();
        String[] tokens = normalized.isEmpty() ? new String[0] : normalized.split("\\s+");

        // 1. Token-level feature projection
        for (int t = 0; t < tokens.length; t++) {
            String token = tokens[t];
            if (token.isBlank()) {
                continue;
            }
            byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
            for (int f = 0; f < HASH_FEATURES_PER_TOKEN; f++) {
                int seed = (f * 31) ^ (tokenBytes.length * 17);
                int hash = hashMurmur3(tokenBytes, seed);
                int index = Math.abs(hash) % DIMENSION;
                float sign = (hash & 1) == 0 ? 1.0f : -1.0f;
                vector[index] += sign * (1.0f / (float) Math.sqrt(tokens.length + 1));
            }
        }

        // 2. Document-level context signature from SHA-256
        byte[] sha = sha256(text);
        for (int i = 0; i < sha.length; i++) {
            int idx = (i * 47) % DIMENSION;
            vector[idx] += ((float) (sha[i] & 0xFF) / 255.0f - 0.5f) * 0.15f;
        }

        // 3. Normalize vector to unit L2 norm: sqrt(sum(v_i^2)) == 1.0
        double sumSq = 0.0;
        for (float v : vector) {
            sumSq += (double) v * v;
        }

        if (sumSq < 1e-12) {
            // Degenerate zero case: set a default unit vector
            vector[0] = 1.0f;
            return vector;
        }

        float norm = (float) Math.sqrt(sumSq);
        for (int i = 0; i < DIMENSION; i++) {
            vector[i] = vector[i] / norm;
        }

        return vector;
    }

    @Override
    public List<float[]> generateBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(generateEmbedding(text));
        }
        return result;
    }

    private static int hashMurmur3(byte[] data, int seed) {
        int h = seed;
        for (byte b : data) {
            h ^= (b & 0xFF);
            h *= 0x5bd1e995;
            h ^= h >>> 15;
        }
        return h;
    }

    private static byte[] sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm missing", e);
        }
    }
}
