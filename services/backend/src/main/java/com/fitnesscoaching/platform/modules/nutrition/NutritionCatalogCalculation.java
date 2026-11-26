package com.fitnesscoaching.platform.modules.nutrition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.UUID;

/** Pure calculation shared by logging and estimate previews; never writes intake. */
final class NutritionCatalogCalculation {
    private NutritionCatalogCalculation() {}
    record Nutrients(BigDecimal energyKcal, BigDecimal proteinG, BigDecimal carbohydrateG, BigDecimal fatG) {}
    record Calculation(BigDecimal grams, Nutrients nutrients) {}

    static BigDecimal quantity(BigDecimal value) {
        if (value == null) throw new NutritionFailure("VALIDATION_FAILED", 400);
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.signum() <= 0 || normalized.scale() > 6
                || normalized.precision() - normalized.scale() > 12)
            throw new NutritionFailure("VALIDATION_FAILED", 400);
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }

    static void basis(String basis) {
        if (basis == null || !Set.of("GRAM", "SERVING", "MILLILITRE").contains(basis))
            throw new NutritionFailure("VALIDATION_FAILED", 400);
    }

    static Calculation calculate(NutritionCatalogService.Food food, BigDecimal quantity, String basis, UUID portionId) {
        quantity = quantity(quantity);
        basis(basis);
        var portion = portionId == null ? null : food.portions().stream()
                .filter(p -> p.id().equals(portionId)).findFirst().orElse(null);
        BigDecimal grams;
        if ("GRAM".equals(basis)) {
            if (portionId != null) throw new NutritionFailure("VALIDATION_FAILED", 400);
            grams = quantity;
        } else if ("SERVING".equals(basis) && portion != null && portion.gramsPerServing() != null
                && portion.factorSource() != null && !portion.factorSource().isBlank()) {
            grams = quantity.multiply(portion.gramsPerServing());
        } else if ("MILLILITRE".equals(basis) && portion != null && portion.gramsPerMl() != null
                && portion.mlFactorSource() != null && !portion.mlFactorSource().isBlank()) {
            grams = quantity.multiply(portion.gramsPerMl());
        } else throw new NutritionFailure("NUTRITION_CONVERSION_UNKNOWN", 409);
        BigDecimal storedGrams = amount(grams);
        if (storedGrams.signum() <= 0) throw new NutritionFailure("NUTRITION_CONVERSION_UNKNOWN", 409);
        return new Calculation(storedGrams, new Nutrients(
                nutrient(food.energyKcalPer100g(), grams), nutrient(food.proteinGPer100g(), grams),
                nutrient(food.carbohydrateGPer100g(), grams), nutrient(food.fatGPer100g(), grams)));
    }

    private static BigDecimal nutrient(BigDecimal per100, BigDecimal grams) {
        return per100 == null ? null : amount(per100.multiply(grams).movePointLeft(2));
    }
    private static BigDecimal amount(BigDecimal value) {
        BigDecimal rounded = value.setScale(6, RoundingMode.HALF_UP);
        if (rounded.signum() < 0 || rounded.precision() - rounded.scale() > 12)
            throw new NutritionFailure("VALIDATION_FAILED", 400);
        return rounded;
    }
}
