package com.fitnesscoaching.platform.modules.content.adapter.out.embedding;

import com.fitnesscoaching.platform.modules.content.application.port.out.EmbeddingModelPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EmbeddingProviderSelectionIntegrationTest")
class EmbeddingProviderSelectionIntegrationTest {

    @Configuration
    @EnableConfigurationProperties(EmbeddingProperties.class)
    @Import(EmbeddingConfiguration.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withBean(RestClient.Builder.class, RestClient::builder);

    @Test
    @DisplayName("Defaults to simulated provider when no provider property is specified")
    void defaultsToSimulated() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EmbeddingModelPort.class);
            EmbeddingModelPort port = context.getBean(EmbeddingModelPort.class);
            assertThat(port).isInstanceOf(DeterministicEmbeddingGenerator.class);
            assertThat(port.provider()).isEqualTo("simulated");
        });
    }

    @Test
    @DisplayName("Selects simulated provider when explicitly configured")
    void selectsSimulatedExplicitly() {
        contextRunner.withPropertyValues("ai.embedding.provider=simulated")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EmbeddingModelPort.class);
                    EmbeddingModelPort port = context.getBean(EmbeddingModelPort.class);
                    assertThat(port).isInstanceOf(DeterministicEmbeddingGenerator.class);
                    assertThat(port.provider()).isEqualTo("simulated");
                });
    }

    @Test
    @DisplayName("Selects openai provider when configured with valid API key")
    void selectsOpenAiWithKey() {
        contextRunner.withPropertyValues(
                        "ai.embedding.provider=openai",
                        "ai.embedding.openai.api-key=sk-test-live-key-12345"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EmbeddingModelPort.class);
                    EmbeddingModelPort port = context.getBean(EmbeddingModelPort.class);
                    assertThat(port).isInstanceOf(OpenAiEmbeddingAdapter.class);
                    assertThat(port.provider()).isEqualTo("openai");
                });
    }

    @Test
    @DisplayName("Fails fast when openai provider is configured but API key is missing")
    void failsFastWhenOpenAiKeyMissing() {
        contextRunner.withPropertyValues("ai.embedding.provider=openai")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("OpenAI embedding API key is required when ai.embedding.provider=openai. " +
                                    "Configure AI_EMBEDDING_API_KEY or OPENAI_API_KEY.");
                });
    }

    @Test
    @DisplayName("Fails fast when provider is unsupported or invalid")
    void failsFastWhenProviderUnsupported() {
        contextRunner.withPropertyValues("ai.embedding.provider=unsupported_provider")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("Unsupported ai.embedding.provider: 'unsupported_provider'. Supported values are 'simulated' and 'openai'.");
                });
    }
}
