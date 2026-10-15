package com.fitnesscoaching.platform.modules.nutrition;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class NutritionBasicController {
    private final NutritionBasicService nutrition;

    public NutritionBasicController(NutritionBasicService nutrition) { this.nutrition = nutrition; }

    @PostMapping("/nutrition/private-foods")
    public ResponseEntity<NutritionBasicService.Food> createFood(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody FoodRequest body) {
        return ResponseEntity.status(201).body(nutrition.createFood(actor(jwt), body.toInput()));
    }

    @PutMapping("/nutrition/private-foods/{foodId}")
    public NutritionBasicService.Food updateFood(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID foodId,
            @Valid @RequestBody FoodRequest body) {
        return nutrition.updateFood(actor(jwt), foodId, body.toInput());
    }

    @GetMapping("/nutrition/private-foods")
    public List<NutritionBasicService.Food> foods(@AuthenticationPrincipal Jwt jwt) {
        return nutrition.foods(actor(jwt));
    }

    @PostMapping("/nutrition/days/{day}/items")
    public ResponseEntity<NutritionBasicService.Day> addItem(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @Valid @RequestBody ItemRequest body) {
        return ResponseEntity.status(201).body(nutrition.addItem(actor(jwt), day, body.toInput()));
    }

    @PostMapping("/nutrition/items/{itemId}/correct")
    public NutritionBasicService.Day correctItem(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID itemId,
            @Valid @RequestBody CorrectionRequest body) {
        return nutrition.correctItem(actor(jwt), itemId, body.toInput());
    }

    @GetMapping("/nutrition/items/{itemId}/corrections")
    public List<NutritionBasicService.Correction> history(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID itemId) {
        return nutrition.history(actor(jwt), itemId);
    }

    @PostMapping("/nutrition/days/{day}/confirm")
    public NutritionBasicService.Day confirm(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @Valid @RequestBody ConfirmRequest body) {
        return nutrition.confirmDay(actor(jwt), day, body.toInput());
    }

    @GetMapping("/nutrition/days/{day}")
    public NutritionBasicService.Day mine(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam String timezone) {
        return nutrition.readMine(actor(jwt), day, timezone);
    }

    @GetMapping("/students/{studentId}/nutrition/days/{day}")
    public NutritionBasicService.Day trainer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID studentId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam String timezone) {
        return nutrition.readForTrainer(actor(jwt), studentId, day, timezone);
    }

    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    public record FoodRequest(@NotBlank String name, @NotBlank String servingLabel,
                              BigDecimal energyKcal, BigDecimal proteinG,
                              BigDecimal carbohydrateG, BigDecimal fatG,
                              Long expectedVersion, @NotBlank String commandKey) {
        NutritionBasicService.FoodInput toInput() {
            return new NutritionBasicService.FoodInput(name, servingLabel, energyKcal, proteinG,
                    carbohydrateG, fatG, expectedVersion, commandKey);
        }
    }
    public record ItemRequest(@NotBlank String timezone, @NotNull Instant consumedAt,
                              @NotBlank String mealType, String description, UUID privateFoodId,
                              BigDecimal servings, @NotBlank String commandKey) {
        NutritionBasicService.ItemInput toInput() {
            return new NutritionBasicService.ItemInput(timezone, consumedAt, mealType, description,
                    privateFoodId, servings, commandKey);
        }
    }
    public record CorrectionRequest(@NotNull Long expectedVersion, String description,
                                    UUID privateFoodId, BigDecimal servings, boolean remove,
                                    @NotBlank String commandKey) {
        NutritionBasicService.CorrectionInput toInput() {
            return new NutritionBasicService.CorrectionInput(expectedVersion, description, privateFoodId,
                    servings, remove, commandKey);
        }
    }
    public record ConfirmRequest(@NotBlank String timezone, @NotNull Long expectedDayVersion,
                                 boolean noIntakeConfirmed, @NotBlank String commandKey) {
        NutritionBasicService.ConfirmInput toInput() {
            return new NutritionBasicService.ConfirmInput(timezone, expectedDayVersion,
                    noIntakeConfirmed, commandKey);
        }
    }
}
