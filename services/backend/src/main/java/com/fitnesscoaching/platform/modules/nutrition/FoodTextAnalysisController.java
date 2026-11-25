package com.fitnesscoaching.platform.modules.nutrition;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import java.math.BigDecimal;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/v1/nutrition/food-analyses")
public class FoodTextAnalysisController {
    private final FoodTextAnalysisService analyses;
    private final FoodAnalysisReviewService reviews;

    public FoodTextAnalysisController(FoodTextAnalysisService analyses, FoodAnalysisReviewService reviews) {
        this.analyses = analyses;
        this.reviews = reviews;
    }

    public record CreateRequest(@NotBlank @Size(max = 4000) String text,
                                @NotNull LocalDate day, @NotBlank String timezone,
                                @NotNull Instant consumedAt, @NotBlank String mealType,
                                @NotBlank @Size(max = 120) String commandKey) {}

    public record FoodAnalysisItemCorrectionRequest(@NotBlank @Size(max = 120) String commandKey,
            @NotNull @Min(0) Long expectedDraftRevision, @NotNull @Min(0) Long expectedItemRevision,
            @JsonProperty(required = true) UUID selectedFoodId,
            @JsonProperty(required = true) UUID selectedRevisionId,
            @JsonProperty(required = true) BigDecimal quantity,
            @JsonProperty(required = true) String quantityBasis,
            @JsonProperty(required = true) UUID portionId) {
        @JsonAnySetter public void unsupported(String field, Object value) {
            throw new IllegalArgumentException("Unsupported correction field: " + field);
        }
    }
    public record FoodAnalysisSelectedItem(@NotNull UUID itemId, @NotNull @Min(0) Long itemRevision,
            @NotNull UUID foodId, @NotNull UUID catalogRevisionId, @NotNull BigDecimal quantity,
            @NotBlank String quantityBasis, @JsonProperty(required = true) UUID portionId) {
        @JsonAnySetter public void unsupported(String field, Object value) {
            throw new IllegalArgumentException("Unsupported selection field: " + field);
        }
    }
    public record FoodAnalysisPreviewRequest(@NotBlank @Size(max = 120) String commandKey,
            @NotNull @Min(0) Long expectedDraftRevision, @NotNull LocalDate day, @NotBlank String timezone,
            @NotNull Instant consumedAt, @NotBlank String mealType,
            @NotNull @Size(min = 1) List<@NotNull @Valid FoodAnalysisSelectedItem> selectedItems) {
        @JsonAnySetter public void unsupported(String field, Object value) {
            throw new IllegalArgumentException("Unsupported preview field: " + field);
        }
    }

    public record FoodAnalysisConfirmRequest(@NotBlank @Size(max = 120) String commandKey,
            @NotNull @Min(0) Long expectedDraftRevision, @NotNull UUID previewId,
            @NotNull @Min(1) Long expectedPreviewRevision, @NotNull LocalDate day, @NotBlank String timezone,
            @NotNull Instant consumedAt, @NotBlank String mealType,
            @NotNull @Size(min = 1) List<@NotNull @Valid FoodAnalysisSelectedItem> selectedItems) {
        @JsonAnySetter public void unsupported(String field, Object value) {
            throw new IllegalArgumentException("Unsupported confirmation field: " + field);
        }
    }

    @PostMapping("/{draftId}/confirmations")
    public ResponseEntity<FoodAnalysisReviewService.FoodAnalysisConfirmationReceipt> confirm(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID draftId,
            @Valid @RequestBody FoodAnalysisConfirmRequest request) {
        var result = reviews.confirm(UUID.fromString(jwt.getSubject()), draftId, request);
        return ResponseEntity.status(result.replayed() ? 200 : 201).body(result);
    }

    @PutMapping("/{draftId}/items/{itemId}")
    public FoodTextAnalysisService.Draft correct(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID draftId, @PathVariable UUID itemId,
            @Valid @RequestBody FoodAnalysisItemCorrectionRequest request) {
        return reviews.correct(UUID.fromString(jwt.getSubject()), draftId, itemId, request);
    }

    @PostMapping("/{draftId}/previews")
    public ResponseEntity<FoodAnalysisReviewService.Preview> preview(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID draftId, @Valid @RequestBody FoodAnalysisPreviewRequest request) {
        var result = reviews.preview(UUID.fromString(jwt.getSubject()), draftId, request);
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.preview());
    }

    @PostMapping
    public ResponseEntity<FoodTextAnalysisService.Draft> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateRequest request) {
        var result = analyses.create(UUID.fromString(jwt.getSubject()), request);
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.draft());
    }

    @GetMapping("/by-command-key/{commandKey}")
    public FoodTextAnalysisService.Draft recover(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String commandKey) {
        return analyses.recover(UUID.fromString(jwt.getSubject()), commandKey);
    }

    @GetMapping("/{draftId}")
    public FoodTextAnalysisService.Draft detail(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID draftId) {
        return analyses.detail(UUID.fromString(jwt.getSubject()), draftId);
    }
}
