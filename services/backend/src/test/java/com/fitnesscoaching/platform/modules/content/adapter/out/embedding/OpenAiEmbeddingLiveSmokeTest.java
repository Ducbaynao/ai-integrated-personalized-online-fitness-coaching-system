package com.fitnesscoaching.platform.modules.content.adapter.out.embedding;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controlled live smoke test for OpenAI text-embedding-3-small adapter.
 * Skips gracefully when RUN_LIVE_OPENAI_TESTS is not 'true' or when no API key is provided,
 * ensuring 100% offline safety for CI/CD and default test executions.
 */
@DisplayName("OpenAI Embedding Live Smoke Test (Controlled)")
class OpenAiEmbeddingLiveSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingLiveSmokeTest.class);

    private static final String SMOKE_INPUT_1 =
            "Khuyến nghị hoạt động thể lực và hạn chế hành vi ít vận động theo hướng dẫn WHO 2020.";
    private static final String SMOKE_INPUT_2 =
            "Tập luyện sức mạnh tăng cường cơ bắp ít nhất 2 ngày mỗi tuần ở cường độ trung bình trở lên.";

    @Test
    @DisplayName("Execute controlled smoke test against OpenAI API when live tests are enabled and credentials provided")
    void testLiveEmbeddingSmokeTest() {
        String apiKey = System.getenv("AI_EMBEDDING_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("OPENAI_API_KEY");
        }
        boolean liveEnabled = Boolean.parseBoolean(System.getenv("RUN_LIVE_OPENAI_TESTS"));

        Assumptions.assumeTrue(liveEnabled && apiKey != null && !apiKey.isBlank(),
                "SKIPPED: Live OpenAI tests are disabled (RUN_LIVE_OPENAI_TESTS != true or missing API key). Offline mode preserved.");

        log.info("Executing controlled live smoke test for OpenAI embedding adapter...");

        EmbeddingProperties properties = new EmbeddingProperties(
                "openai",
                new EmbeddingProperties.OpenAiProperties(
                        apiKey,
                        "https://api.openai.com",
                        "text-embedding-3-small",
                        "openai/text-embedding-3-small/1536",
                        1536,
                        5000,
                        10000,
                        2,
                        15000
                )
        );

        OpenAiEmbeddingAdapter adapter = new OpenAiEmbeddingAdapter(properties, RestClient.builder());

        // 1. Single input test
        long startTime = System.currentTimeMillis();
        float[] vector = adapter.generateEmbedding(SMOKE_INPUT_1);
        long durationMs = System.currentTimeMillis() - startTime;

        assertThat(vector).isNotNull();
        assertThat(vector).hasSize(1536);
        for (int i = 0; i < vector.length; i++) {
            assertThat(Float.isNaN(vector[i]))
                    .withFailMessage("Vector contains NaN at index " + i)
                    .isFalse();
            assertThat(Float.isInfinite(vector[i]))
                    .withFailMessage("Vector contains Infinity at index " + i)
                    .isFalse();
        }

        assertThat(adapter.modelSpace()).isEqualTo("openai/text-embedding-3-small/1536");
        assertThat(adapter.modelVersionId()).isEqualTo(OpenAiEmbeddingAdapter.OPENAI_MODEL_VERSION_ID);

        log.info("Single embedding smoke test PASSED: dimension=1536, durationMs={}", durationMs);

        // 2. Small 2-element batch test
        long batchStartTime = System.currentTimeMillis();
        List<float[]> batchVectors = adapter.generateBatch(List.of(SMOKE_INPUT_1, SMOKE_INPUT_2));
        long batchDurationMs = System.currentTimeMillis() - batchStartTime;

        assertThat(batchVectors).hasSize(2);
        assertThat(batchVectors.get(0)).hasSize(1536);
        assertThat(batchVectors.get(1)).hasSize(1536);

        log.info("Batch embedding smoke test PASSED: count=2, durationMs={}", batchDurationMs);
    }
}
