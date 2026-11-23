package com.fitnesscoaching.platform.modules.nutrition;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/nutrition/food-analyses")
public class FoodTextAnalysisController {
    private final FoodTextAnalysisService analyses;

    public FoodTextAnalysisController(FoodTextAnalysisService analyses) {
        this.analyses = analyses;
    }

    public record CreateRequest(@NotBlank @Size(max = 4000) String text,
                                @NotNull LocalDate day, @NotBlank String timezone,
                                @NotNull Instant consumedAt, @NotBlank String mealType,
                                @NotBlank @Size(max = 120) String commandKey) {}

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
