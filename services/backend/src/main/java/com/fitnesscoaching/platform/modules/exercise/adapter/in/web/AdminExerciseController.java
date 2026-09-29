package com.fitnesscoaching.platform.modules.exercise.adapter.in.web;

import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto.AdminExerciseDraftRequest;
import com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto.AdminExercisePageResponse;
import com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto.AdminExerciseResponse;
import com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto.ArchiveExerciseRequest;
import com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto.CanonicalReplacementRequest;
import com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto.ExerciseVersionRequest;
import com.fitnesscoaching.platform.modules.exercise.adapter.in.web.dto.UpdateAdminExerciseRequest;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.AdminExerciseCatalogUseCase;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.AdminExerciseQuery;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExercise;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseLifecycleStatus;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/exercises")
@PreAuthorize("hasRole('ADMIN')")
public class AdminExerciseController {

    private final AdminExerciseCatalogUseCase useCase;

    public AdminExerciseController(AdminExerciseCatalogUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping
    public ResponseEntity<AdminExercisePageResponse> search(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        ExerciseLifecycleStatus lifecycleStatus = parseStatus(status);
        AdminExerciseQuery request = new AdminExerciseQuery(
                UUID.fromString(jwt.getSubject()), query, lifecycleStatus, page, size);
        return ResponseEntity.ok(AdminExercisePageResponse.fromDomain(useCase.search(request)));
    }

    @GetMapping("/{exerciseId}")
    public ResponseEntity<AdminExerciseResponse> detail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID exerciseId
    ) {
        return ResponseEntity.ok(AdminExerciseResponse.fromDomain(
                useCase.getDetail(UUID.fromString(jwt.getSubject()), exerciseId)));
    }

    @PostMapping
    public ResponseEntity<AdminExerciseResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AdminExerciseDraftRequest request
    ) {
        AdminExercise created = useCase.createDraft(UUID.fromString(jwt.getSubject()), request.toDomain());
        return ResponseEntity.created(URI.create("/api/v1/admin/exercises/" + created.id()))
                .body(AdminExerciseResponse.fromDomain(created));
    }

    @PutMapping("/{exerciseId}")
    public ResponseEntity<AdminExerciseResponse> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID exerciseId,
            @Valid @RequestBody UpdateAdminExerciseRequest request
    ) {
        return ResponseEntity.ok(AdminExerciseResponse.fromDomain(useCase.updateDraft(
                UUID.fromString(jwt.getSubject()), exerciseId, request.expectedVersion(),
                request.exercise().toDomain())));
    }

    @PostMapping("/{exerciseId}/activate")
    public ResponseEntity<AdminExerciseResponse> activate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID exerciseId,
            @Valid @RequestBody ExerciseVersionRequest request
    ) {
        return ResponseEntity.ok(AdminExerciseResponse.fromDomain(useCase.activate(
                UUID.fromString(jwt.getSubject()), exerciseId, request.expectedVersion())));
    }

    @PostMapping("/{exerciseId}/archive")
    public ResponseEntity<AdminExerciseResponse> archive(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID exerciseId,
            @Valid @RequestBody ArchiveExerciseRequest request
    ) {
        return ResponseEntity.ok(AdminExerciseResponse.fromDomain(useCase.archive(
                UUID.fromString(jwt.getSubject()), exerciseId, request.expectedVersion(), request.reason())));
    }

    @PutMapping("/{exerciseId}/canonical-replacement")
    public ResponseEntity<AdminExerciseResponse> setCanonicalReplacement(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID exerciseId,
            @Valid @RequestBody CanonicalReplacementRequest request
    ) {
        return ResponseEntity.ok(AdminExerciseResponse.fromDomain(useCase.setCanonicalReplacement(
                UUID.fromString(jwt.getSubject()), exerciseId, request.expectedVersion(),
                request.targetExerciseId(), request.reason())));
    }

    private ExerciseLifecycleStatus parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return ExerciseLifecycleStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ApplicationValidationException("Invalid Exercise lifecycle status",
                    List.of(new FieldErrorDto("status", "Invalid", "Status must be DRAFT, ACTIVE, or ARCHIVED")));
        }
    }
}
