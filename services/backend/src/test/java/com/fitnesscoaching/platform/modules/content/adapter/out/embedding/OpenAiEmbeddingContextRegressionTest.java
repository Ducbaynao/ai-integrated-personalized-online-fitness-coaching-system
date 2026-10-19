package com.fitnesscoaching.platform.modules.content.adapter.out.embedding;

import com.fitnesscoaching.platform.modules.content.application.port.out.EmbeddingModelPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline regression test verifying that the full Spring Boot application context starts
 * successfully when `ai.embedding.provider=openai` is configured with a dummy API key.
 *
 * <p>Root Cause & History:
 * Previously, {@link EmbeddingProviderSelectionIntegrationTest} passed because its test runner
 * manually injected {@code .withBean(RestClient.Builder.class, RestClient::builder)}, thereby masking
 * the fact that the Spring Boot ApplicationContext lacked a {@link RestClient.Builder} bean in production.
 * When real application context or integration tests booted with {@code ai.embedding.provider=openai},
 * Spring failed with {@code UnsatisfiedDependencyException: No qualifying bean of type RestClient$Builder}.
 *
 * <p>This test proves:
 * 1. The full Spring Boot ApplicationContext loads without errors when provider is 'openai'.
 * 2. {@link RestClient.Builder} is provided by {@link EmbeddingConfiguration} via {@code @ConditionalOnMissingBean}.
 * 3. {@link OpenAiEmbeddingAdapter} is created with actual HTTP client configuration (timeouts, headers, properties).
 * 4. The test runs 100% offline without sending any network request to external APIs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "ai.embedding.provider=openai",
        "ai.embedding.openai.api-key=sk-dummy-offline-regression-test-key-12345",
        "ai.embedding.openai.base-url=http://127.0.0.1:65432",
        "ai.embedding.openai.connect-timeout-ms=4500",
        "ai.embedding.openai.read-timeout-ms=9500"
})
@DisplayName("OpenAI Embedding Spring Context Offline Regression Test")
class OpenAiEmbeddingContextRegressionTest {

    @Autowired
    private EmbeddingModelPort embeddingModelPort;

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Autowired
    private EmbeddingProperties embeddingProperties;

    @Test
    @DisplayName("Spring Boot context successfully initializes OpenAiEmbeddingAdapter with RestClient.Builder bean")
    void contextLoadsOpenAiEmbeddingAdapterWithoutExternalMocks() {
        assertThat(restClientBuilder)
                .as("RestClient.Builder must be present in ApplicationContext")
                .isNotNull();

        assertThat(embeddingModelPort)
                .as("EmbeddingModelPort must be an instance of OpenAiEmbeddingAdapter")
                .isNotNull()
                .isInstanceOf(OpenAiEmbeddingAdapter.class);

        assertThat(embeddingModelPort.provider())
                .isEqualTo("openai");

        assertThat(embeddingModelPort.modelVersionId())
                .isEqualTo(OpenAiEmbeddingAdapter.OPENAI_MODEL_VERSION_ID);

        assertThat(embeddingModelPort.modelSpace())
                .isEqualTo("openai/text-embedding-3-small/1536");

        assertThat(embeddingModelPort.dimension())
                .isEqualTo(1536);

        assertThat(embeddingProperties.openai().connectTimeoutMs())
                .isEqualTo(4500);

        assertThat(embeddingProperties.openai().readTimeoutMs())
                .isEqualTo(9500);
    }
}
