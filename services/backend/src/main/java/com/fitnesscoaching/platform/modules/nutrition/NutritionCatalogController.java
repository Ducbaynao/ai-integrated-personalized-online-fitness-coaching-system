package com.fitnesscoaching.platform.modules.nutrition;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class NutritionCatalogController {
    private final NutritionCatalogService catalog;
    public NutritionCatalogController(NutritionCatalogService catalog) { this.catalog = catalog; }

    @GetMapping("/nutrition/catalog/foods")
    public List<NutritionCatalogService.Food> published() { return catalog.published(); }

    @GetMapping("/nutrition/catalog/foods/{foodId}")
    public NutritionCatalogService.Food detail(@PathVariable UUID foodId) { return catalog.detail(foodId); }

    @GetMapping("/admin/nutrition/catalog/foods")
    @PreAuthorize("hasRole('ADMIN')")
    public List<NutritionCatalogService.Food> adminFoods(@AuthenticationPrincipal Jwt jwt) {
        return catalog.adminFoods(actor(jwt));
    }

    @PostMapping("/admin/nutrition/catalog/foods")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<NutritionCatalogService.Food> draft(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DraftRequest body) {
        return ResponseEntity.status(201).body(catalog.draft(actor(jwt), new NutritionCatalogService.DraftInput(
                body.fdcId(), body.sourceRelease(), body.sourceDate(), body.revision(), body.commandKey())));
    }

    @PostMapping("/admin/nutrition/catalog/foods/{foodId}/revisions")
    @PreAuthorize("hasRole('ADMIN')")
    public NutritionCatalogService.Food revise(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID foodId,
            @Valid @RequestBody RevisionRequest body) {
        return catalog.revise(actor(jwt), foodId, body.expectedVersion(), body.revision(), body.commandKey());
    }

    @PostMapping("/admin/nutrition/catalog/foods/{foodId}/transitions")
    @PreAuthorize("hasRole('ADMIN')")
    public NutritionCatalogService.Food transition(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID foodId,
            @Valid @RequestBody TransitionRequest body) {
        return catalog.transition(actor(jwt), foodId, body.expectedVersion(),
                body.action(), body.reason(), body.commandKey());
    }

    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    public record DraftRequest(long fdcId, @NotBlank String sourceRelease,
                               @NotNull LocalDate sourceDate,
                               @NotNull NutritionCatalogService.RevisionInput revision,
                               @NotBlank String commandKey) {}
    public record RevisionRequest(long expectedVersion,
                                  @NotNull NutritionCatalogService.RevisionInput revision,
                                  @NotBlank String commandKey) {}
    public record TransitionRequest(long expectedVersion, @NotBlank String action,
                                    @NotBlank String reason, @NotBlank String commandKey) {}
}
