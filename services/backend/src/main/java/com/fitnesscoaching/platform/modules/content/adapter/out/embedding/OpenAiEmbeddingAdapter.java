package com.fitnesscoaching.platform.modules.content.adapter.out.embedding;

import com.fitnesscoaching.platform.common.exception.AiOutputValidationException;
import com.fitnesscoaching.platform.common.exception.AiProviderException;
import com.fitnesscoaching.platform.common.exception.AiTimeoutException;
import com.fitnesscoaching.platform.modules.content.application.port.out.EmbeddingModelPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Production-ready embedding adapter for OpenAI text-embedding-3-small (1536 dimensions).
 * Implements EmbeddingModelPort, produces strictly validated unit vectors mapped to input indices,
 * enforces bounded HTTP retries with backoff and overall deadlines, and never falls back silently to simulated.
 */
public class OpenAiEmbeddingAdapter implements EmbeddingModelPort {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingAdapter.class);

    public static final String OPENAI_PROVIDER = "openai";
    public static final String OPENAI_MODEL_CODE = "text-embedding-3-small";
    public static final String OPENAI_VERSION_LABEL = "1.0.0";
    public static final String OPENAI_MODEL_SPACE = "openai/text-embedding-3-small/1536";
    public static final UUID OPENAI_MODEL_VERSION_ID = UUID.fromString("e1000000-0000-0000-0000-000000000002");
    public static final int EXPECTED_DIMENSION = 1536;
    private static final String EMBEDDINGS_PATH = "/v1/embeddings";

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final EmbeddingProperties properties;
    private final RestClient restClient;
    private final Sleeper sleeper;
    private final Random random = new Random();

    public OpenAiEmbeddingAdapter(EmbeddingProperties properties, RestClient restClient) {
        this(properties, restClient, Thread::sleep);
    }

    public OpenAiEmbeddingAdapter(EmbeddingProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, createRestClient(properties, restClientBuilder), Thread::sleep);
    }

    public OpenAiEmbeddingAdapter(EmbeddingProperties properties, RestClient.Builder restClientBuilder, Sleeper sleeper) {
        this(properties, createRestClient(properties, restClientBuilder), sleeper);
    }

    public OpenAiEmbeddingAdapter(EmbeddingProperties properties, RestClient restClient, Sleeper sleeper) {
        if (properties == null || properties.openai() == null || properties.openai().apiKey() == null || properties.openai().apiKey().isBlank()) {
            throw new IllegalStateException("OpenAI embedding API key is required when ai.embedding.provider=openai. " +
                    "Configure AI_EMBEDDING_API_KEY or OPENAI_API_KEY.");
        }
        this.properties = properties;
        this.restClient = restClient;
        this.sleeper = sleeper != null ? sleeper : Thread::sleep;
    }

    private static RestClient createRestClient(EmbeddingProperties properties, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.openai().connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.openai().readTimeoutMs()));

        return builder
                .baseUrl(properties.openai().baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.openai().apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public int dimension() {
        return EXPECTED_DIMENSION;
    }

    @Override
    public String provider() {
        return OPENAI_PROVIDER;
    }

    @Override
    public String modelCode() {
        return OPENAI_MODEL_CODE;
    }

    @Override
    public String versionLabel() {
        return OPENAI_VERSION_LABEL;
    }

    @Override
    public String modelSpace() {
        return OPENAI_MODEL_SPACE;
    }

    @Override
    public UUID modelVersionId() {
        return OPENAI_MODEL_VERSION_ID;
    }

    @Override
    public float[] generateEmbedding(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Text for embedding must not be null or blank");
        }
        List<float[]> results = generateBatch(List.of(text));
        if (results.isEmpty()) {
            throw new AiOutputValidationException("OpenAI embedding returned empty results for single input");
        }
        return results.get(0);
    }

    @Override
    public List<float[]> generateBatch(List<String> texts) {
        if (texts == null) {
            throw new IllegalArgumentException("Batch texts list must not be null");
        }
        if (texts.isEmpty()) {
            return List.of();
        }
        for (String t : texts) {
            if (t == null || t.isBlank()) {
                throw new IllegalArgumentException("Batch texts must not contain null or blank entries");
            }
        }

        int maxAttempts = Math.max(1, properties.openai().maxAttempts());
        long overallDeadlineMs = properties.openai().overallDeadlineMs();
        long startTime = System.currentTimeMillis();

        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= overallDeadlineMs) {
                log.warn("OpenAI embedding exceeded overall deadline of {}ms before attempt {}", overallDeadlineMs, attempt);
                throw new AiTimeoutException("OpenAI embedding invocation exceeded overall deadline of " + overallDeadlineMs + "ms");
            }

            try {
                log.info("Dispatching OpenAI embedding request for {} inputs (attempt {}/{}, model: {})",
                        texts.size(), attempt, maxAttempts, properties.openai().model());

                OpenAiEmbeddingRequest request = new OpenAiEmbeddingRequest(
                        texts,
                        properties.openai().model(),
                        EXPECTED_DIMENSION
                );

                OpenAiEmbeddingResponse response = restClient.post()
                        .uri(EMBEDDINGS_PATH)
                        .body(request)
                        .retrieve()
                        .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                            int code = resp.getStatusCode().value();
                            String body = new String(resp.getBody().readAllBytes());
                            log.warn("OpenAI embedding API returned 4xx client error: HTTP {} body={}", code, sanitizeLogBody(body));
                            if (code == 401 || code == 403) {
                                throw new AiProviderException("OpenAI embedding authentication failed (HTTP " + code + "): " + sanitizeErrorMessage(body));
                            }
                            if (code == 429) {
                                if (isQuotaExceeded(body)) {
                                    throw new AiProviderException("OpenAI quota or spend limit exceeded (HTTP 429): " + sanitizeErrorMessage(body));
                                }
                                String retryAfterHeader = resp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                                throw new RetryableRateLimitException("OpenAI rate limit exceeded (HTTP 429)", retryAfterHeader, body);
                            }
                            throw new AiProviderException("OpenAI rejected embedding request (HTTP " + code + "): " + sanitizeErrorMessage(body));
                        })
                        .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                            int code = resp.getStatusCode().value();
                            String body = new String(resp.getBody().readAllBytes());
                            log.warn("OpenAI embedding API returned 5xx server error: HTTP {} body={}", code, sanitizeLogBody(body));
                            throw new RetryableServerException("OpenAI embedding server error (HTTP " + code + ")", code, body);
                        })
                        .body(OpenAiEmbeddingResponse.class);

                return validateAndMapResponse(response, texts.size());

            } catch (AiProviderException ex) {
                // Non-retryable provider error (e.g. 401, 403, 400, quota exceeded 429)
                throw ex;
            } catch (AiOutputValidationException ex) {
                // Malformed response / invalid vector / dimension mismatch: non-retryable
                throw ex;
            } catch (RetryableRateLimitException ex) {
                lastException = ex;
                if (attempt >= maxAttempts) {
                    log.warn("OpenAI rate limit exceeded after {} attempts", maxAttempts);
                    throw new AiProviderException("OpenAI rate limit exceeded after " + maxAttempts + " attempts: " + ex.getMessage());
                }
                long backoffMs = computeBackoff(attempt, ex.getRetryAfterHeader(), overallDeadlineMs, startTime);
                sleepBackoff(backoffMs, overallDeadlineMs, startTime);
            } catch (RetryableServerException ex) {
                lastException = ex;
                if (attempt >= maxAttempts) {
                    log.warn("OpenAI server error persistent after {} attempts (HTTP {})", maxAttempts, ex.getStatusCode());
                    throw new AiProviderException("OpenAI embedding provider error after " + maxAttempts + " attempts (HTTP " + ex.getStatusCode() + ")");
                }
                long backoffMs = computeBackoff(attempt, null, overallDeadlineMs, startTime);
                sleepBackoff(backoffMs, overallDeadlineMs, startTime);
            } catch (ResourceAccessException ex) {
                lastException = ex;
                Throwable cause = ex.getCause();
                boolean isTimeout = cause instanceof SocketTimeoutException ||
                        (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("timed out"));

                if (attempt >= maxAttempts) {
                    if (isTimeout) {
                        log.warn("OpenAI embedding request timed out after {} attempts", maxAttempts);
                        throw new AiTimeoutException("OpenAI embedding request timed out after " + maxAttempts + " attempts: " + ex.getMessage());
                    } else {
                        log.warn("OpenAI embedding network error after {} attempts: {}", maxAttempts, ex.getMessage());
                        throw new AiProviderException("Unable to connect to OpenAI embedding service after " + maxAttempts + " attempts: " + ex.getMessage());
                    }
                }
                long backoffMs = computeBackoff(attempt, null, overallDeadlineMs, startTime);
                sleepBackoff(backoffMs, overallDeadlineMs, startTime);
            } catch (RestClientResponseException ex) {
                int code = ex.getStatusCode().value();
                String body = ex.getResponseBodyAsString();
                log.warn("OpenAI embedding HTTP error {} body={}", code, sanitizeLogBody(body));
                if (code == 401 || code == 403) {
                    throw new AiProviderException("OpenAI embedding authentication failed (HTTP " + code + ")");
                }
                if (code == 429) {
                    if (isQuotaExceeded(body)) {
                        throw new AiProviderException("OpenAI quota or spend limit exceeded (HTTP 429): " + sanitizeErrorMessage(body));
                    }
                    if (attempt >= maxAttempts) {
                        throw new AiProviderException("OpenAI rate limit exceeded after " + maxAttempts + " attempts");
                    }
                    String retryAfter = ex.getResponseHeaders() != null ? ex.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER) : null;
                    long backoffMs = computeBackoff(attempt, retryAfter, overallDeadlineMs, startTime);
                    sleepBackoff(backoffMs, overallDeadlineMs, startTime);
                    continue;
                }
                if (code >= 500) {
                    if (attempt >= maxAttempts) {
                        throw new AiProviderException("OpenAI embedding provider error after " + maxAttempts + " attempts (HTTP " + code + ")");
                    }
                    long backoffMs = computeBackoff(attempt, null, overallDeadlineMs, startTime);
                    sleepBackoff(backoffMs, overallDeadlineMs, startTime);
                    continue;
                }
                throw new AiProviderException("OpenAI embedding client error (HTTP " + code + "): " + sanitizeErrorMessage(body));
            } catch (Exception ex) {
                log.error("Unexpected error invoking OpenAI embeddings", ex);
                throw new AiProviderException("Unexpected error during OpenAI embedding invocation: " + ex.getMessage(), ex);
            }
        }

        if (lastException != null) {
            throw new AiProviderException("OpenAI embedding failed after " + maxAttempts + " attempts: " + lastException.getMessage(), lastException);
        }
        throw new AiProviderException("OpenAI embedding failed without result");
    }

    private List<float[]> validateAndMapResponse(OpenAiEmbeddingResponse response, int expectedSize) {
        if (response == null) {
            throw new AiOutputValidationException("OpenAI embedding response was empty (null response body)");
        }
        if (response.data() == null) {
            throw new AiOutputValidationException("OpenAI embedding response missing 'data' array");
        }
        if (response.data().size() != expectedSize) {
            throw new AiOutputValidationException("OpenAI embedding response size mismatch: expected " +
                    expectedSize + " vectors but got " + response.data().size());
        }

        float[][] orderedVectors = new float[expectedSize][];
        boolean[] seenIndices = new boolean[expectedSize];

        for (OpenAiEmbeddingResponse.EmbeddingData item : response.data()) {
            if (item == null) {
                throw new AiOutputValidationException("OpenAI embedding response contains null embedding item");
            }
            int index = item.index();
            if (index < 0 || index >= expectedSize) {
                throw new AiOutputValidationException("OpenAI embedding response contains index out of bounds: " +
                        index + " (expected range [0, " + (expectedSize - 1) + "])");
            }
            if (seenIndices[index]) {
                throw new AiOutputValidationException("OpenAI embedding response contains duplicate index: " + index);
            }
            seenIndices[index] = true;

            float[] vector = item.embedding();
            if (vector == null) {
                throw new AiOutputValidationException("OpenAI embedding response contains null vector at index " + index);
            }
            if (vector.length != EXPECTED_DIMENSION) {
                throw new AiOutputValidationException("OpenAI embedding dimension mismatch: expected " +
                        EXPECTED_DIMENSION + " but was " + vector.length + " at index " + index);
            }

            for (int i = 0; i < vector.length; i++) {
                float val = vector[i];
                if (Float.isNaN(val) || Float.isInfinite(val)) {
                    throw new AiOutputValidationException("OpenAI embedding vector contains non-finite float value (" +
                            val + ") at index " + index + ", position " + i);
                }
            }

            orderedVectors[index] = vector;
        }

        for (int i = 0; i < expectedSize; i++) {
            if (!seenIndices[i] || orderedVectors[i] == null) {
                throw new AiOutputValidationException("OpenAI embedding response missing vector for input index " + i);
            }
        }

        return Arrays.asList(orderedVectors);
    }

    private static boolean isQuotaExceeded(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String lower = body.toLowerCase();
        return lower.contains("insufficient_quota") ||
                lower.contains("organization_spend_limit_exceeded") ||
                lower.contains("project_spend_limit_exceeded") ||
                lower.contains("exceeded your current quota") ||
                lower.contains("billing details");
    }

    private long computeBackoff(int attempt, String retryAfterHeader, long overallDeadlineMs, long startTime) {
        long remaining = overallDeadlineMs - (System.currentTimeMillis() - startTime);
        if (remaining <= 200L) {
            throw new AiTimeoutException("OpenAI embedding invocation exceeded overall deadline of " + overallDeadlineMs + "ms before retry");
        }
        if (retryAfterHeader != null && !retryAfterHeader.isBlank()) {
            try {
                double seconds = Double.parseDouble(retryAfterHeader.trim());
                long millis = (long) (seconds * 1000);
                if (millis >= remaining) {
                    throw new AiTimeoutException("OpenAI rate limit Retry-After (" + millis + "ms) exceeds remaining deadline (" + remaining + "ms)");
                }
                return Math.max(200L, Math.min(millis, remaining - 200L));
            } catch (NumberFormatException ignored) {
                // fall through to exponential backoff
            }
        }
        long base = 500L * (1L << (attempt - 1)); // 500ms, 1000ms
        long jitter = random.nextInt(250);
        long delay = base + jitter;
        return Math.max(100L, Math.min(delay, remaining - 100L));
    }

    private void sleepBackoff(long backoffMs, long overallDeadlineMs, long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed + backoffMs >= overallDeadlineMs) {
            throw new AiTimeoutException("OpenAI embedding invocation exceeded overall deadline of " + overallDeadlineMs + "ms during backoff sleep");
        }
        try {
            sleeper.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiTimeoutException("OpenAI embedding retry interrupted", e);
        }
    }

    private static String sanitizeLogBody(String body) {
        if (body == null) return "";
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }

    private static String sanitizeErrorMessage(String body) {
        if (body == null || body.isBlank()) return "Unknown error";
        return body.replaceAll("(?i)bearer\\s+[a-zA-Z0-9_-]+", "Bearer [REDACTED]")
                .replaceAll("(?i)sk-[a-zA-Z0-9_-]+", "[REDACTED]");
    }

    private static class RetryableRateLimitException extends RuntimeException {
        private final String retryAfterHeader;
        private final String responseBody;

        public RetryableRateLimitException(String message, String retryAfterHeader, String responseBody) {
            super(message);
            this.retryAfterHeader = retryAfterHeader;
            this.responseBody = responseBody;
        }

        public String getRetryAfterHeader() {
            return retryAfterHeader;
        }
    }

    private static class RetryableServerException extends RuntimeException {
        private final int statusCode;
        private final String responseBody;

        public RetryableServerException(String message, int statusCode, String responseBody) {
            super(message);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
