package com.fitnesscoaching.platform.modules.content.adapter.out.embedding;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AiOutputValidationException;
import com.fitnesscoaching.platform.common.exception.AiProviderException;
import com.fitnesscoaching.platform.common.exception.AiTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("OpenAiEmbeddingAdapter Unit Tests")
class OpenAiEmbeddingAdapterTest {

    private static final String API_KEY = "sk-test-mock-api-key-12345";
    private static final String BASE_URL = "https://api.openai.com";
    private static final String ENDPOINT = "https://api.openai.com/v1/embeddings";

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private ObjectMapper objectMapper;
    private List<Long> sleepRecords;
    private OpenAiEmbeddingAdapter.Sleeper testSleeper;
    private EmbeddingProperties embeddingProperties;
    private OpenAiEmbeddingAdapter adapter;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "Bearer " + API_KEY)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        objectMapper = new ObjectMapper();
        sleepRecords = new ArrayList<>();
        testSleeper = sleepRecords::add;

        EmbeddingProperties.OpenAiProperties openAiProperties = new EmbeddingProperties.OpenAiProperties(
                API_KEY,
                BASE_URL,
                "text-embedding-3-small",
                "openai/text-embedding-3-small/1536",
                1536,
                5_000,
                10_000,
                2,
                15_000
        );
        embeddingProperties = new EmbeddingProperties("openai", openAiProperties);

        adapter = new OpenAiEmbeddingAdapter(embeddingProperties, restClientBuilder.build(), testSleeper);
    }

    private List<Float> createValidEmbedding(float fillValue) {
        List<Float> vector = new ArrayList<>(1536);
        for (int i = 0; i < 1536; i++) {
            vector.add(fillValue);
        }
        return vector;
    }

    private String buildSuccessResponseBody(List<List<Float>> embeddings) throws Exception {
        List<OpenAiEmbeddingResponse.EmbeddingData> dataList = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            float[] arr = new float[embeddings.get(i).size()];
            for (int j = 0; j < arr.length; j++) {
                arr[j] = embeddings.get(i).get(j);
            }
            dataList.add(new OpenAiEmbeddingResponse.EmbeddingData(i, arr));
        }
        OpenAiEmbeddingResponse resp = new OpenAiEmbeddingResponse(
                "list",
                dataList,
                "text-embedding-3-small",
                new OpenAiEmbeddingResponse.Usage(10, 10)
        );
        return objectMapper.writeValueAsString(resp);
    }

    @Test
    @DisplayName("Metadata matches OpenAI contract specifications and V42 registry")
    void metadataMatchesRegistry() {
        assertThat(adapter.dimension()).isEqualTo(1536);
        assertThat(adapter.provider()).isEqualTo("openai");
        assertThat(adapter.modelCode()).isEqualTo("text-embedding-3-small");
        assertThat(adapter.modelSpace()).isEqualTo("openai/text-embedding-3-small/1536");
        assertThat(adapter.versionLabel()).isEqualTo("1.0.0");
        assertThat(adapter.modelVersionId()).isEqualTo(UUID.fromString("e1000000-0000-0000-0000-000000000002"));
    }

    @Test
    @DisplayName("Successfully generates embedding for single text with valid headers and payload")
    void generateSingleEmbeddingSuccess() throws Exception {
        String responseJson = buildSuccessResponseBody(List.of(createValidEmbedding(0.05f)));

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.model").value("text-embedding-3-small"))
                .andExpect(jsonPath("$.dimensions").value(1536))
                .andExpect(jsonPath("$.encoding_format").value("float"))
                .andExpect(jsonPath("$.input[0]").value("Khuyến nghị vận động tim mạch"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        float[] embedding = adapter.generateEmbedding("Khuyến nghị vận động tim mạch");

        mockServer.verify();
        assertThat(embedding).hasSize(1536);
        assertThat(embedding[0]).isEqualTo(0.05f);
        assertThat(sleepRecords).isEmpty();
    }

    @Test
    @DisplayName("Successfully processes batch and sorts embeddings by index even when out-of-order")
    void generateBatchSuccessOutOfOrder() throws Exception {
        List<Float> vec0 = createValidEmbedding(0.1f);
        List<Float> vec1 = createValidEmbedding(0.2f);

        // Put index 1 first, index 0 second
        float[] arr0 = new float[1536];
        float[] arr1 = new float[1536];
        for (int i = 0; i < 1536; i++) {
            arr0[i] = vec0.get(i);
            arr1[i] = vec1.get(i);
        }
        List<OpenAiEmbeddingResponse.EmbeddingData> dataList = List.of(
                new OpenAiEmbeddingResponse.EmbeddingData(1, arr1),
                new OpenAiEmbeddingResponse.EmbeddingData(0, arr0)
        );
        OpenAiEmbeddingResponse resp = new OpenAiEmbeddingResponse(
                "list",
                dataList,
                "text-embedding-3-small",
                new OpenAiEmbeddingResponse.Usage(20, 20)
        );
        String responseJson = objectMapper.writeValueAsString(resp);

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.input.length()").value(2))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        List<float[]> results = adapter.generateBatch(List.of("Text A", "Text B"));

        mockServer.verify();
        assertThat(results).hasSize(2);
        assertThat(results.get(0)[0]).isEqualTo(0.1f); // index 0
        assertThat(results.get(1)[0]).isEqualTo(0.2f); // index 1
    }

    @Test
    @DisplayName("Empty batch returns empty list without calling remote server")
    void emptyBatchReturnsImmediately() {
        List<float[]> results = adapter.generateBatch(Collections.emptyList());
        assertThat(results).isEmpty();
        mockServer.verify();
    }

    @Test
    @DisplayName("Throws IllegalArgumentException for null input or blank single text")
    void invalidInputThrows() {
        assertThatThrownBy(() -> adapter.generateEmbedding(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.generateEmbedding("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.generateBatch(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects duplicate index in response")
    void rejectsDuplicateIndex() throws Exception {
        float[] arr = new float[1536];
        List<OpenAiEmbeddingResponse.EmbeddingData> dataList = List.of(
                new OpenAiEmbeddingResponse.EmbeddingData(0, arr),
                new OpenAiEmbeddingResponse.EmbeddingData(0, arr)
        );
        String responseJson = objectMapper.writeValueAsString(new OpenAiEmbeddingResponse(
                "list", dataList, "text-embedding-3-small", null
        ));

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.generateBatch(List.of("A", "B")))
                .isInstanceOf(AiOutputValidationException.class)
                .hasMessageContaining("duplicate index");
    }

    @Test
    @DisplayName("Rejects out of bounds index in response")
    void rejectsOutOfBoundsIndex() throws Exception {
        float[] arr = new float[1536];
        List<OpenAiEmbeddingResponse.EmbeddingData> dataList = List.of(
                new OpenAiEmbeddingResponse.EmbeddingData(2, arr) // only 1 input, index 2 is invalid
        );
        String responseJson = objectMapper.writeValueAsString(new OpenAiEmbeddingResponse(
                "list", dataList, "text-embedding-3-small", null
        ));

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.generateEmbedding("A"))
                .isInstanceOf(AiOutputValidationException.class)
                .hasMessageContaining("out of bounds");
    }

    @Test
    @DisplayName("Rejects count mismatch between input and output")
    void rejectsCountMismatch() throws Exception {
        String responseJson = buildSuccessResponseBody(List.of(createValidEmbedding(0.1f))); // 1 item returned for 2 inputs

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.generateBatch(List.of("A", "B")))
                .isInstanceOf(AiOutputValidationException.class)
                .hasMessageContaining("size mismatch");
    }

    @Test
    @DisplayName("Rejects invalid vector dimension (e.g., 512 instead of 1536)")
    void rejectsInvalidDimension() throws Exception {
        float[] arr512 = new float[512];
        List<OpenAiEmbeddingResponse.EmbeddingData> dataList = List.of(
                new OpenAiEmbeddingResponse.EmbeddingData(0, arr512)
        );
        String responseJson = objectMapper.writeValueAsString(new OpenAiEmbeddingResponse(
                "list", dataList, "text-embedding-3-small", null
        ));

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.generateEmbedding("A"))
                .isInstanceOf(AiOutputValidationException.class)
                .hasMessageContaining("dimension mismatch");
    }

    @Test
    @DisplayName("Rejects non-finite vector components (NaN / Infinity)")
    void rejectsNonFiniteComponents() throws Exception {
        float[] arr = new float[1536];
        arr[10] = Float.NaN;
        List<OpenAiEmbeddingResponse.EmbeddingData> dataList = List.of(
                new OpenAiEmbeddingResponse.EmbeddingData(0, arr)
        );
        String responseJson = objectMapper.writeValueAsString(new OpenAiEmbeddingResponse(
                "list", dataList, "text-embedding-3-small", null
        ));

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.generateEmbedding("A"))
                .isInstanceOf(AiOutputValidationException.class)
                .hasMessageContaining("non-finite");
    }

    @Test
    @DisplayName("Authentication failure 401 fails fast without retrying")
    void authFailure401FailsFast() {
        String errorJson = """
                {"error": {"message": "Incorrect API key provided", "type": "invalid_request_error", "code": "invalid_api_key"}}
                """;

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorJson));

        assertThatThrownBy(() -> adapter.generateEmbedding("Test text"))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("HTTP 401");

        mockServer.verify();
        assertThat(sleepRecords).isEmpty();
    }

    @Test
    @DisplayName("Forbidden 403 fails fast without retrying")
    void forbidden403FailsFast() {
        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": {\"message\": \"Access denied\"}}"));

        assertThatThrownBy(() -> adapter.generateEmbedding("Test text"))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("HTTP 403");

        mockServer.verify();
        assertThat(sleepRecords).isEmpty();
    }

    @Test
    @DisplayName("Client error 400 fails fast without retrying")
    void clientError400FailsFast() {
        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": {\"message\": \"Invalid parameter\"}}"));

        assertThatThrownBy(() -> adapter.generateEmbedding("Test text"))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("HTTP 400");

        mockServer.verify();
        assertThat(sleepRecords).isEmpty();
    }

    @Test
    @DisplayName("Quota exceeded (insufficient_quota) 429 fails fast without retrying")
    void quotaExceeded429FailsFast() {
        String quotaErrorJson = """
                {
                  "error": {
                    "message": "You exceeded your current quota, please check your plan and billing details.",
                    "type": "insufficient_quota",
                    "param": null,
                    "code": "insufficient_quota"
                  }
                }
                """;

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(quotaErrorJson));

        assertThatThrownBy(() -> adapter.generateEmbedding("Test text"))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("quota or spend limit exceeded");

        mockServer.verify();
        assertThat(sleepRecords).isEmpty(); // MUST NOT retry quota errors
    }

    @Test
    @DisplayName("Rate limit 429 retries with backoff and succeeds on attempt 2")
    void rateLimit429RetriesAndSucceeds() throws Exception {
        String rateLimitJson = """
                {
                  "error": {
                    "message": "Rate limit reached for requests",
                    "type": "requests",
                    "param": null,
                    "code": "rate_limit_exceeded"
                  }
                }
                """;
        String successJson = buildSuccessResponseBody(List.of(createValidEmbedding(0.08f)));

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(rateLimitJson));

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(successJson, MediaType.APPLICATION_JSON));

        float[] result = adapter.generateEmbedding("Test text");

        mockServer.verify();
        assertThat(result).hasSize(1536);
        assertThat(sleepRecords).hasSize(1);
        assertThat(sleepRecords.get(0)).isGreaterThanOrEqualTo(200L); // backoff applied
    }

    @Test
    @DisplayName("Server error 500 retries and succeeds on attempt 2")
    void serverError500RetriesAndSucceeds() throws Exception {
        String successJson = buildSuccessResponseBody(List.of(createValidEmbedding(0.09f)));

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": {\"message\": \"Internal server error\"}}"));

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(successJson, MediaType.APPLICATION_JSON));

        float[] result = adapter.generateEmbedding("Test text");

        mockServer.verify();
        assertThat(result).hasSize(1536);
        assertThat(sleepRecords).hasSize(1);
    }

    @Test
    @DisplayName("Persistent 503 error exhausts retries and throws AiProviderException")
    void persistentServerErrorExhaustsRetries() {
        mockServer.expect(ExpectedCount.max(2), requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": {\"message\": \"Service overloaded\"}}"));

        assertThatThrownBy(() -> adapter.generateEmbedding("Test text"))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("after 2 attempts");

        mockServer.verify();
        assertThat(sleepRecords).hasSize(1); // 1 sleep between 2 attempts
    }

    @Test
    @DisplayName("No silent fallback: failure always raises exception without returning simulated vector")
    void noSilentFallbackOnFailure() {
        mockServer.expect(ExpectedCount.max(2), requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": {\"message\": \"Bad gateway\"}}"));

        assertThatThrownBy(() -> adapter.generateEmbedding("Critical knowledge text"))
                .isInstanceOf(AiProviderException.class)
                .isNotInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Fails fast on initialization when API key is missing or blank")
    void missingApiKeyThrowsFast() {
        EmbeddingProperties.OpenAiProperties emptyKeyProps = new EmbeddingProperties.OpenAiProperties(
                "",
                BASE_URL,
                "text-embedding-3-small",
                "openai/text-embedding-3-small/1536",
                1536,
                5_000,
                10_000,
                2,
                15_000
        );
        EmbeddingProperties missingKeyEmbeddingProps = new EmbeddingProperties("openai", emptyKeyProps);

        assertThatThrownBy(() -> new OpenAiEmbeddingAdapter(missingKeyEmbeddingProps, RestClient.builder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OpenAI embedding API key is required");
    }

    @Test
    @DisplayName("Retry-After header exceeding deadline throws AiTimeoutException immediately without sleeping")
    void retryAfterExceedingDeadlineThrowsTimeout() {
        String rateLimitJson = """
                {"error": {"message": "Rate limit reached", "type": "requests", "code": "rate_limit_exceeded"}}
                """;

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Retry-After", "30") // 30s > 15s deadline
                        .body(rateLimitJson));

        assertThatThrownBy(() -> adapter.generateEmbedding("Test text"))
                .isInstanceOf(AiTimeoutException.class)
                .hasMessageContaining("Retry-After");

        mockServer.verify();
        assertThat(sleepRecords).isEmpty(); // MUST NOT sleep when exceeding deadline
    }

    @Test
    @DisplayName("Retry-After header within deadline sleeps exact duration and succeeds on attempt 2")
    void retryAfterWithinDeadlineSleepsAndRetries() throws Exception {
        String rateLimitJson = """
                {"error": {"message": "Rate limit reached", "type": "requests", "code": "rate_limit_exceeded"}}
                """;
        String successJson = buildSuccessResponseBody(List.of(createValidEmbedding(0.05f)));

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Retry-After", "1") // 1 second
                        .body(rateLimitJson));

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(successJson, MediaType.APPLICATION_JSON));

        float[] result = adapter.generateEmbedding("Test text");

        mockServer.verify();
        assertThat(result).hasSize(1536);
        assertThat(sleepRecords).containsExactly(1000L);
    }

    @Test
    @DisplayName("HTTP 200 with null or empty response body throws AiOutputValidationException")
    void emptyResponseBodyThrowsValidationException() {
        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.generateEmbedding("Test text"))
                .isInstanceOf(AiOutputValidationException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("HTTP 200 with missing data array throws AiOutputValidationException")
    void nullDataArrayThrowsValidationException() {
        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"object\": \"list\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.generateEmbedding("Test text"))
                .isInstanceOf(AiOutputValidationException.class)
                .hasMessageContaining("missing 'data' array");
    }

    @Test
    @DisplayName("HTTP 200 with null embedding vector throws AiOutputValidationException")
    void nullVectorItemThrowsValidationException() {
        String responseJson = """
                {"object": "list", "data": [{"object": "embedding", "index": 0, "embedding": null}]}
                """;

        mockServer.expect(ExpectedCount.once(), requestTo(ENDPOINT))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.generateEmbedding("Test text"))
                .isInstanceOf(AiOutputValidationException.class)
                .hasMessageContaining("null vector");
    }

    @Test
    @DisplayName("URI normalization helper strips trailing slashes and redundant /v1 suffixes")
    void verifyNormalizeBaseUrl() {
        assertThat(OpenAiEmbeddingAdapter.normalizeBaseUrl(null)).isEqualTo("https://api.openai.com");
        assertThat(OpenAiEmbeddingAdapter.normalizeBaseUrl("")).isEqualTo("https://api.openai.com");
        assertThat(OpenAiEmbeddingAdapter.normalizeBaseUrl("   ")).isEqualTo("https://api.openai.com");
        assertThat(OpenAiEmbeddingAdapter.normalizeBaseUrl("https://api.openai.com")).isEqualTo("https://api.openai.com");
        assertThat(OpenAiEmbeddingAdapter.normalizeBaseUrl("https://api.openai.com/")).isEqualTo("https://api.openai.com");
        assertThat(OpenAiEmbeddingAdapter.normalizeBaseUrl("https://api.openai.com/v1")).isEqualTo("https://api.openai.com");
        assertThat(OpenAiEmbeddingAdapter.normalizeBaseUrl("https://api.openai.com/v1/")).isEqualTo("https://api.openai.com");
        assertThat(OpenAiEmbeddingAdapter.normalizeBaseUrl("http://127.0.0.1:8000")).isEqualTo("http://127.0.0.1:8000");
        assertThat(OpenAiEmbeddingAdapter.normalizeBaseUrl("http://127.0.0.1:8000/v1")).isEqualTo("http://127.0.0.1:8000");
        assertThat(OpenAiEmbeddingAdapter.normalizeBaseUrl("http://127.0.0.1:8000/v1/")).isEqualTo("http://127.0.0.1:8000");
    }

    @Test
    @DisplayName("Actual HTTP wire request path is /v1/embeddings when baseUrl ends with /v1")
    void dispatchesToNormalizedWirePathWhenBaseUrlHasTrailingV1() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        int port = server.getAddress().getPort();
        java.util.concurrent.atomic.AtomicReference<String> capturedPath = new java.util.concurrent.atomic.AtomicReference<>();

        String responseJson = """
                {"object": "list", "data": [{"object": "embedding", "index": 0, "embedding": %s}], "usage": {"prompt_tokens": 5, "total_tokens": 5}}
                """.formatted(objectMapper.valueToTree(Collections.nCopies(1536, 0.025f)).toString());

        server.createContext("/", exchange -> {
            capturedPath.set(exchange.getRequestURI().toString());
            byte[] bytes = responseJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        try {
            EmbeddingProperties props = new EmbeddingProperties(
                    "openai",
                    new EmbeddingProperties.OpenAiProperties(
                            API_KEY,
                            "http://127.0.0.1:" + port + "/v1",
                            "text-embedding-3-small",
                            "openai/text-embedding-3-small/1536",
                            1536,
                            3000,
                            6000,
                            2,
                            15000
                    )
            );

            OpenAiEmbeddingAdapter customAdapter = new OpenAiEmbeddingAdapter(props, RestClient.builder(), testSleeper);
            float[] vector = customAdapter.generateEmbedding("Wire verification with /v1");

            assertThat(vector).hasSize(1536);
            assertThat(capturedPath.get()).isEqualTo("/v1/embeddings");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Actual HTTP wire request path is /v1/embeddings when baseUrl has trailing slash and /v1/")
    void dispatchesToNormalizedWirePathWhenBaseUrlHasTrailingSlash() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        int port = server.getAddress().getPort();
        java.util.concurrent.atomic.AtomicReference<String> capturedPath = new java.util.concurrent.atomic.AtomicReference<>();

        String responseJson = """
                {"object": "list", "data": [{"object": "embedding", "index": 0, "embedding": %s}], "usage": {"prompt_tokens": 5, "total_tokens": 5}}
                """.formatted(objectMapper.valueToTree(Collections.nCopies(1536, 0.025f)).toString());

        server.createContext("/", exchange -> {
            capturedPath.set(exchange.getRequestURI().toString());
            byte[] bytes = responseJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        try {
            EmbeddingProperties props = new EmbeddingProperties(
                    "openai",
                    new EmbeddingProperties.OpenAiProperties(
                            API_KEY,
                            "http://127.0.0.1:" + port + "/v1/",
                            "text-embedding-3-small",
                            "openai/text-embedding-3-small/1536",
                            1536,
                            3000,
                            6000,
                            2,
                            15000
                    )
            );

            OpenAiEmbeddingAdapter customAdapter = new OpenAiEmbeddingAdapter(props, RestClient.builder(), testSleeper);
            float[] vector = customAdapter.generateEmbedding("Wire verification with /v1/");

            assertThat(vector).hasSize(1536);
            assertThat(capturedPath.get()).isEqualTo("/v1/embeddings");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Actual HTTP wire request path is /v1/embeddings with default baseUrl without /v1")
    void dispatchesToNormalizedWirePathWithDefaultBaseUrl() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        int port = server.getAddress().getPort();
        java.util.concurrent.atomic.AtomicReference<String> capturedPath = new java.util.concurrent.atomic.AtomicReference<>();

        String responseJson = """
                {"object": "list", "data": [{"object": "embedding", "index": 0, "embedding": %s}], "usage": {"prompt_tokens": 5, "total_tokens": 5}}
                """.formatted(objectMapper.valueToTree(Collections.nCopies(1536, 0.025f)).toString());

        server.createContext("/", exchange -> {
            capturedPath.set(exchange.getRequestURI().toString());
            byte[] bytes = responseJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        try {
            EmbeddingProperties props = new EmbeddingProperties(
                    "openai",
                    new EmbeddingProperties.OpenAiProperties(
                            API_KEY,
                            "http://127.0.0.1:" + port,
                            "text-embedding-3-small",
                            "openai/text-embedding-3-small/1536",
                            1536,
                            3000,
                            6000,
                            2,
                            15000
                    )
            );

            OpenAiEmbeddingAdapter customAdapter = new OpenAiEmbeddingAdapter(props, RestClient.builder(), testSleeper);
            float[] vector = customAdapter.generateEmbedding("Wire verification with root host");

            assertThat(vector).hasSize(1536);
            assertThat(capturedPath.get()).isEqualTo("/v1/embeddings");
        } finally {
            server.stop(0);
        }
    }
}
