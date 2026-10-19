package com.fitnesscoaching.platform.modules.content.adapter.out.embedding;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Request payload for OpenAI Embeddings API (POST /v1/embeddings).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiEmbeddingRequest(
        List<String> input,
        String model,
        Integer dimensions,
        String encoding_format
) {
    public OpenAiEmbeddingRequest(List<String> input, String model, Integer dimensions) {
        this(input, model, dimensions, "float");
    }
}
