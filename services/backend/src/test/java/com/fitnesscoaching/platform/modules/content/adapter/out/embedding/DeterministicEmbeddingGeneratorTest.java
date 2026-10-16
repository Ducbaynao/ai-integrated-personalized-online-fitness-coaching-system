package com.fitnesscoaching.platform.modules.content.adapter.out.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DeterministicEmbeddingGeneratorTest {

    private final DeterministicEmbeddingGenerator generator = new DeterministicEmbeddingGenerator();

    @Test
    @DisplayName("Generator produces 1536-dimensional L2-normalized unit vectors")
    void producesNormalized1536Vectors() {
        assertThat(generator.dimension()).isEqualTo(1536);
        assertThat(generator.provider()).isEqualTo("simulated");
        assertThat(generator.modelCode()).isEqualTo("simulated/deterministic-1536");
        assertThat(generator.modelSpace()).isEqualTo("simulated/deterministic-1536/1.0.0");
        assertThat(generator.versionLabel()).isEqualTo("1.0.0");
        assertThat(generator.modelVersionId()).isEqualTo(java.util.UUID.fromString("e1000000-0000-0000-0000-000000000001"));

        float[] embedding = generator.generateEmbedding("Hướng dẫn vận động thể lực WHO 2020");
        assertThat(embedding).hasSize(1536);

        // Verify L2 norm == 1.0 (unit vector for pgvector cosine distance)
        double sumSq = 0.0;
        for (float v : embedding) {
            sumSq += v * v;
        }
        assertThat(Math.sqrt(sumSq)).isCloseTo(1.0, within(1e-4));
    }

    @Test
    @DisplayName("Deterministic embeddings: identical text always yields identical vectors")
    void deterministicVectors() {
        String text = "150-300 phút hoạt động thể lực vừa mỗi tuần";
        float[] v1 = generator.generateEmbedding(text);
        float[] v2 = generator.generateEmbedding(text);

        assertThat(v1).containsExactly(v2);
    }

    @Test
    @DisplayName("Different texts yield distinct vector representations")
    void differentTextsYieldDistinctVectors() {
        float[] v1 = generator.generateEmbedding("Khuyến nghị vận động tim mạch và sức bền");
        float[] v2 = generator.generateEmbedding("Nhu cầu bổ sung protein và năng lượng");

        assertThat(v1).isNotEqualTo(v2);

        // Cosine similarity between distinct texts should be < 1.0
        double dotProduct = 0.0;
        for (int i = 0; i < 1536; i++) {
            dotProduct += v1[i] * v2[i];
        }
        assertThat(dotProduct).isLessThan(0.99);
    }

    @Test
    @DisplayName("Batch generation matches individual generation")
    void batchMatchesIndividual() {
        List<String> texts = List.of(
                "Đoạn 1: Lợi ích sức khỏe của đi bộ nhanh",
                "Đoạn 2: Tần suất rèn luyện sức mạnh cơ bắp"
        );

        List<float[]> batch = generator.generateBatch(texts);
        assertThat(batch).hasSize(2);
        assertThat(batch.get(0)).containsExactly(generator.generateEmbedding(texts.get(0)));
        assertThat(batch.get(1)).containsExactly(generator.generateEmbedding(texts.get(1)));
    }
}
