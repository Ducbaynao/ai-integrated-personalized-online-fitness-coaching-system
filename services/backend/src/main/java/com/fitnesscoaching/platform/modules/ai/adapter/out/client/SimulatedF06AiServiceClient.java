package com.fitnesscoaching.platform.modules.ai.adapter.out.client;

import com.fitnesscoaching.platform.modules.ai.application.port.out.F06AiServiceClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic unknown fallback. It never supplies food IDs, portions or nutrients. */
public final class SimulatedF06AiServiceClient implements F06AiServiceClient {
    private final ObjectMapper mapper;

    public SimulatedF06AiServiceClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String estimateTextJson(String text) {
        try {
            var missing = List.of("FOOD_IDENTITY", "QUANTITY", "UNIT");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("detectedName", text.trim().substring(0, Math.min(text.trim().length(), 200)));
            item.put("candidateNames", List.of());
            item.put("quantity", null);
            item.put("unitText", null);
            item.put("confidence", null);
            item.put("missingData", missing);
            item.put("limitations", List.of("No food identity or portion was inferred."));
            return mapper.writeValueAsString(Map.of(
                    "resultType", "ESTIMATE",
                    "items", List.of(item),
                    "missingData", missing,
                    "limitations", List.of("Simulated provider; use catalog/manual entry.")));
        } catch (Exception failed) {
            throw new IllegalStateException("Could not serialize F06 simulated estimate", failed);
        }
    }
}
