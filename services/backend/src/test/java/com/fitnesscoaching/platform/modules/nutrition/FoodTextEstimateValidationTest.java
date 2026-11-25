package com.fitnesscoaching.platform.modules.nutrition;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.Collections;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FoodTextEstimateValidationTest {
    private final FoodTextAnalysisService service = new FoodTextAnalysisService(
            null, null, null, null, null, new ObjectMapper(), null, null);

    private String item(String candidates, String missing) {
        return """
                {"detectedName":"oats","candidateNames":%s,"quantity":null,
                 "unitText":null,"confidence":null,"missingData":%s,"limitations":[]}
                """.formatted(candidates, missing);
    }

    private String output(String items, String missing) {
        return """
                {"resultType":"ESTIMATE","items":[%s],"missingData":%s,"limitations":[]}
                """.formatted(items, missing);
    }

    @Test void itemLimitMatchesSchema() {
        String item = item("[]", "[]");
        assertThat(service.parseEstimate(output(String.join(",", Collections.nCopies(20, item)), "[]")))
                .hasSize(20);
        rejects(output(String.join(",", Collections.nCopies(21, item)), "[]"));
    }

    @Test void candidateLengthsAndUniqueArraysMatchSchema() {
        assertThat(service.parseEstimate(output(item("[\"" + "a".repeat(200) + "\"]", "[]"), "[]")))
                .hasSize(1);
        rejects(output(item("[\"\"]", "[]"), "[]"));
        rejects(output(item("[\"" + "a".repeat(201) + "\"]", "[]"), "[]"));
        rejects(output(item("[\"oats\",\"oats\"]", "[]"), "[]"));
        rejects(output(item("[]", "[\"UNIT\",\"UNIT\"]"), "[]"));
        rejects(output(item("[]", "[]"), "[\"UNIT\",\"UNIT\"]"));
    }

    private void rejects(String json) {
        assertThatThrownBy(() -> service.parseEstimate(json)).isInstanceOf(NutritionFailure.class)
                .extracting("code").isEqualTo("NUTRITION_AI_OUTPUT_INVALID");
    }
}
