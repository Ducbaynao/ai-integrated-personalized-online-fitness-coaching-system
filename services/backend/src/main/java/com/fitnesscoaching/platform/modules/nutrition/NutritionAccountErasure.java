package com.fitnesscoaching.platform.modules.nutrition;

import java.util.UUID;

/** Nutrition-owned author-proven text minimization; unresolved food/media remains pending. */
public interface NutritionAccountErasure {
    void minimizeAuthoredMealText(UUID userId);
}
