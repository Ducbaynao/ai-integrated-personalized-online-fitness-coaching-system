package com.fitnesscoaching.platform.modules.content.adapter.out.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response payload from OpenAI Embeddings API (POST /v1/embeddings).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiEmbeddingResponse(
        String object,
        List<EmbeddingData> data,
        String model,
        Usage usage
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmbeddingData(
            String object,
            int index,
            float[] embedding
    ) {
        public EmbeddingData(int index, float[] embedding) {
            this("embedding", index, embedding);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("total_tokens") int totalTokens
    ) {
    }
}
