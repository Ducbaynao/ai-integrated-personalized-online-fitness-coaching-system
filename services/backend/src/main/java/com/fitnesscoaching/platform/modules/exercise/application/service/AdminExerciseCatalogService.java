package com.fitnesscoaching.platform.modules.exercise.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.AdminExerciseNotFoundException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.ExerciseCanonicalConflictException;
import com.fitnesscoaching.platform.common.exception.ExerciseLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.ExerciseVersionConflictException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.exercise.application.model.AdminExerciseFormMetadata;
import com.fitnesscoaching.platform.modules.exercise.application.model.AdminExercisePage;
import com.fitnesscoaching.platform.modules.exercise.application.model.CanonicalReplacementExercise;
import com.fitnesscoaching.platform.modules.exercise.application.model.CanonicalReplacementPreview;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.AdminExerciseCatalogUseCase;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.AdminExerciseQuery;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseDraftData;
import com.fitnesscoaching.platform.modules.exercise.application.port.out.AdminExerciseRepository;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExercise;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseEquipment;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseMuscle;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseVariation;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseDifficulty;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseLifecycleStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserPermissionQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class AdminExerciseCatalogService implements AdminExerciseCatalogUseCase {

    private static final int MAX_PAGE_SIZE = 100;
    private static final List<String> DIFFICULTIES = Arrays.stream(ExerciseDifficulty.values())
            .map(Enum::name).toList();
    private static final Set<String> INVOLVEMENTS = Set.of("PRIMARY", "SECONDARY", "STABILIZER");
    private static final Set<String> REQUIREMENTS = Set.of("REQUIRED", "OPTIONAL", "ALTERNATIVE");

    private final AdminExerciseRepository repository;
    private final UserAccountStatusQuery accountStatusQuery;
    private final UserRoleQuery roleQuery;
    private final UserPermissionQuery permissionQuery;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AdminExerciseCatalogService(
            AdminExerciseRepository repository,
            UserAccountStatusQuery accountStatusQuery,
            UserRoleQuery roleQuery,
            UserPermissionQuery permissionQuery,
            AuditService auditService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.accountStatusQuery = accountStatusQuery;
        this.roleQuery = roleQuery;
        this.permissionQuery = permissionQuery;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public AdminExercisePage search(AdminExerciseQuery query) {
        if (query == null) throw validation("query", "NotNull", "Admin Exercise query is required");
        verifyCatalogManager(query.adminUserId());
        if (query.page() < 0) throw validation("page", "Min", "Page must be zero or greater");
        if (query.size() < 1 || query.size() > MAX_PAGE_SIZE) {
            throw validation("size", "Range", "Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if ((long) query.page() * query.size() > Integer.MAX_VALUE) {
            throw validation("page", "Range", "Requested page is outside the supported range");
        }
        String search = trimToNull(query.query());
        if (search != null && search.length() > 120) {
            throw validation("query", "Size", "Search query must not exceed 120 characters");
        }
        return repository.search(new AdminExerciseQuery(
                query.adminUserId(), search, query.status(), query.page(), query.size()));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminExerciseFormMetadata getFormMetadata(UUID adminUserId) {
        verifyCatalogManager(adminUserId);
        AdminExerciseFormMetadata metadata = repository.findFormMetadata();
        return new AdminExerciseFormMetadata(
                metadata.categories(), metadata.muscleGroups(), metadata.equipment(), metadata.tags(),
                DIFFICULTIES, metadata.movementPatterns());
    }

    @Override
    @Transactional(readOnly = true)
    public AdminExercise getDetail(UUID adminUserId, UUID exerciseId) {
        verifyCatalogManager(adminUserId);
        return find(exerciseId);
    }

    @Override
    @Transactional(readOnly = true)
    public CanonicalReplacementPreview getCanonicalReplacementPreview(UUID adminUserId, UUID exerciseId) {
        verifyCatalogManager(adminUserId);
        AdminExercise source = find(exerciseId);
        requireStatus(source, ExerciseLifecycleStatus.ARCHIVED,
                "Canonical replacement can only be managed for an ARCHIVED Exercise");
        CanonicalReplacementExercise sourceReference = new CanonicalReplacementExercise(
                source.id(), source.code(), source.name(), source.status());
        CanonicalReplacementExercise currentTarget = source.canonicalReplacementId() == null
                ? null
                : repository.findCanonicalReplacementExerciseById(source.canonicalReplacementId())
                .orElseThrow(() -> new ExerciseCanonicalConflictException(
                        "Current canonical replacement target is unavailable"));
        return new CanonicalReplacementPreview(
                sourceReference, source.version(), currentTarget,
                CanonicalReplacementPreview.UsageImpact.notAvailable());
    }

    @Override
    public AdminExercise createDraft(UUID adminUserId, ExerciseDraftData draft) {
        verifyCatalogManager(adminUserId);
        ExerciseDraftData normalized = normalizeAndValidateDraft(draft);
        validateReferences(normalized);
        Instant now = clock.instant();
        UUID exerciseId = UUID.randomUUID();
        AdminExercise created = repository.createDraft(exerciseId, adminUserId, normalized, now);
        audit(adminUserId, "EXERCISE_DRAFT_CREATED", exerciseId, null, snapshot(created), null, now);
        return created;
    }

    @Override
    public AdminExercise updateDraft(UUID adminUserId, UUID exerciseId, long expectedVersion, ExerciseDraftData draft) {
        verifyCatalogManager(adminUserId);
        requireVersion(expectedVersion);
        AdminExercise current = findForUpdate(exerciseId);
        requireStatus(current, ExerciseLifecycleStatus.DRAFT, "Only DRAFT Exercises can be edited");
        requireExpectedVersion(current, expectedVersion);
        ExerciseDraftData normalized = normalizeAndValidateDraft(draft);
        validateReferences(normalized);
        Instant now = clock.instant();
        if (!repository.updateDraft(exerciseId, expectedVersion, normalized, now)) throw versionConflict();
        AdminExercise updated = find(exerciseId);
        audit(adminUserId, "EXERCISE_DRAFT_UPDATED", exerciseId, snapshot(current), snapshot(updated), null, now);
        return updated;
    }

    @Override
    public AdminExercise activate(UUID adminUserId, UUID exerciseId, long expectedVersion) {
        verifyCatalogManager(adminUserId);
        requireVersion(expectedVersion);
        AdminExercise current = findForUpdate(exerciseId);
        requireStatus(current, ExerciseLifecycleStatus.DRAFT, "Only a DRAFT Exercise can be activated");
        requireExpectedVersion(current, expectedVersion);
        List<FieldErrorDto> errors = new ArrayList<>();
        if (trimToNull(current.name()) == null) errors.add(field("name", "NotBlank", "Name is required for activation"));
        if (current.categoryCode() == null) errors.add(field("categoryCode", "NotNull", "Category is required for activation"));
        if (current.difficulty() == null) errors.add(field("difficulty", "NotNull", "Difficulty is required for activation"));
        if (trimToNull(current.movementPattern()) == null) {
            errors.add(field("movementPattern", "NotBlank", "Movement pattern is required for activation"));
        }
        repository.findActivationProblems(exerciseId).forEach(problem ->
                errors.add(field(problem, "InactiveReference", "Exercise references inactive catalog metadata")));
        if (!errors.isEmpty()) {
            throw new ApplicationValidationException("Exercise is incomplete and cannot be activated", errors);
        }
        Instant now = clock.instant();
        if (!repository.transition(exerciseId, expectedVersion, ExerciseLifecycleStatus.DRAFT,
                ExerciseLifecycleStatus.ACTIVE, now)) throw versionConflict();
        AdminExercise activated = find(exerciseId);
        audit(adminUserId, "EXERCISE_ACTIVATED", exerciseId, snapshot(current), snapshot(activated), null, now);
        return activated;
    }

    @Override
    public AdminExercise archive(UUID adminUserId, UUID exerciseId, long expectedVersion, String reason) {
        verifyCatalogManager(adminUserId);
        requireVersion(expectedVersion);
        String normalizedReason = requireReason(reason);
        AdminExercise current = findForUpdate(exerciseId);
        requireStatus(current, ExerciseLifecycleStatus.ACTIVE, "Only an ACTIVE Exercise can be archived");
        requireExpectedVersion(current, expectedVersion);
        if (repository.isCanonicalTarget(exerciseId)) {
            throw new ExerciseCanonicalConflictException(
                    "Exercise is a canonical replacement target and cannot be archived");
        }
        Instant now = clock.instant();
        if (!repository.transition(exerciseId, expectedVersion, ExerciseLifecycleStatus.ACTIVE,
                ExerciseLifecycleStatus.ARCHIVED, now)) throw versionConflict();
        AdminExercise archived = find(exerciseId);
        audit(adminUserId, "EXERCISE_ARCHIVED", exerciseId, snapshot(current), snapshot(archived),
                Map.of("reason", normalizedReason), now);
        return archived;
    }

    @Override
    public AdminExercise setCanonicalReplacement(
            UUID adminUserId,
            UUID exerciseId,
            long expectedVersion,
            UUID targetExerciseId,
            String reason
    ) {
        verifyCatalogManager(adminUserId);
        requireVersion(expectedVersion);
        String normalizedReason = requireReason(reason);
        AdminExercise source = findForUpdate(exerciseId);
        requireStatus(source, ExerciseLifecycleStatus.ARCHIVED,
                "Canonical replacement can only be managed for an ARCHIVED Exercise");
        requireExpectedVersion(source, expectedVersion);
        UUID previousTarget = source.canonicalReplacementId();
        Instant now = clock.instant();
        if (targetExerciseId == null) {
            if (!repository.clearCanonicalReplacement(exerciseId, expectedVersion, now)) throw versionConflict();
        } else {
            if (exerciseId.equals(targetExerciseId)) {
                throw new ExerciseCanonicalConflictException("Exercise cannot be its own canonical replacement");
            }
            AdminExercise target = findForUpdate(targetExerciseId);
            if (target.status() != ExerciseLifecycleStatus.ACTIVE) {
                throw new ExerciseCanonicalConflictException("Canonical replacement target must be ACTIVE");
            }
            if (target.canonicalReplacementId() != null || repository.hasCanonicalReplacement(targetExerciseId)) {
                throw new ExerciseCanonicalConflictException("Canonical replacement chains are not permitted");
            }
            if (repository.isCanonicalTarget(exerciseId)) {
                throw new ExerciseCanonicalConflictException("Canonical replacement cycles are not permitted");
            }
            if (!repository.setCanonicalReplacement(exerciseId, expectedVersion, targetExerciseId,
                    adminUserId, normalizedReason, now)) throw versionConflict();
        }
        AdminExercise updated = find(exerciseId);
        String action = targetExerciseId == null ? "EXERCISE_CANONICAL_REPLACEMENT_CLEARED"
                : previousTarget == null ? "EXERCISE_CANONICAL_REPLACEMENT_SET"
                : "EXERCISE_CANONICAL_REPLACEMENT_CHANGED";
        audit(adminUserId, action, exerciseId, snapshot(source), snapshot(updated),
                Map.of("reason", normalizedReason), now);
        return updated;
    }

    private ExerciseDraftData normalizeAndValidateDraft(ExerciseDraftData draft) {
        if (draft == null) throw validation("body", "NotNull", "Exercise draft is required");
        List<FieldErrorDto> errors = new ArrayList<>();
        String code = upper(draft.code());
        String name = trimToNull(draft.name());
        if (code == null) errors.add(field("code", "NotBlank", "Code is required"));
        if (name == null) errors.add(field("name", "NotBlank", "Name is required"));
        if (code != null && code.length() > 100) errors.add(field("code", "Size", "Code must not exceed 100 characters"));
        if (name != null && name.length() > 180) errors.add(field("name", "Size", "Name must not exceed 180 characters"));
        String difficulty = upper(draft.difficulty());
        if (difficulty != null && !DIFFICULTIES.contains(difficulty)) {
            errors.add(field("difficulty", "Invalid", "Difficulty is not supported"));
        }
        List<String> tags = normalizeCodes(draft.tagCodes(), "tagCodes", errors);
        List<AdminExerciseVariation> variations = new ArrayList<>();
        Set<String> variationCodes = new HashSet<>();
        int defaultCount = 0;
        for (int index = 0; index < draft.variations().size(); index++) {
            AdminExerciseVariation value = draft.variations().get(index);
            String prefix = "variations[" + index + "]";
            String variationCode = upper(value.code());
            String variationName = trimToNull(value.name());
            String variationDifficulty = upper(value.difficulty());
            if (variationCode == null) errors.add(field(prefix + ".code", "NotBlank", "Variation code is required"));
            if (variationName == null) errors.add(field(prefix + ".name", "NotBlank", "Variation name is required"));
            if (variationCode != null && !variationCodes.add(variationCode)) {
                errors.add(field(prefix + ".code", "Duplicate", "Variation codes must be unique"));
            }
            if (variationDifficulty != null && !DIFFICULTIES.contains(variationDifficulty)) {
                errors.add(field(prefix + ".difficulty", "Invalid", "Variation difficulty is not supported"));
            }
            if (value.defaultVariation() && value.active()) defaultCount++;
            List<AdminExerciseMuscle> muscles = value.muscles().stream().map(muscle -> {
                String involvement = upper(muscle.involvement());
                if (!INVOLVEMENTS.contains(involvement)) {
                    errors.add(field(prefix + ".muscles", "Invalid", "Muscle involvement is not supported"));
                }
                return new AdminExerciseMuscle(upper(muscle.muscleGroupCode()), involvement);
            }).toList();
            List<AdminExerciseEquipment> equipment = value.equipment().stream().map(item -> {
                String requirement = upper(item.requirement());
                if (!REQUIREMENTS.contains(requirement)) {
                    errors.add(field(prefix + ".equipment", "Invalid", "Equipment requirement is not supported"));
                }
                return new AdminExerciseEquipment(upper(item.equipmentCode()), requirement);
            }).toList();
            variations.add(new AdminExerciseVariation(value.id(), variationCode, variationName,
                    trimToNull(value.description()), trimToNull(value.instructions()), variationDifficulty,
                    value.defaultVariation(), value.active(), muscles, equipment));
        }
        if (defaultCount > 1) errors.add(field("variations", "Invalid", "Only one active default variation is allowed"));
        if (!errors.isEmpty()) throw new ApplicationValidationException("Exercise draft validation failed", errors);
        return new ExerciseDraftData(code, name, upper(draft.categoryCode()), trimToNull(draft.description()),
                trimToNull(draft.instructions()), difficulty, upper(draft.movementPattern()), draft.unilateral(),
                tags, variations);
    }

    private void validateReferences(ExerciseDraftData draft) {
        List<String> problems = repository.findDraftReferenceProblems(draft);
        if (!problems.isEmpty()) {
            throw new ApplicationValidationException("Exercise references unavailable catalog metadata",
                    problems.stream().map(problem -> field(problem, "InvalidReference",
                            "Referenced catalog value does not exist or is inactive")).toList());
        }
    }

    private void verifyCatalogManager(UUID adminUserId) {
        if (adminUserId == null) throw new AccessDeniedException("Authenticated administrator is required");
        AccountStatus status = accountStatusQuery.getAccountStatus(adminUserId)
                .orElseThrow(() -> new AccessDeniedException("Administrator account does not exist"));
        if (status != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException("Administrator account is not ACTIVE");
        }
        if (!roleQuery.hasActiveRole(adminUserId, "ADMIN")
                || !permissionQuery.hasActivePermission(adminUserId, "CATALOG_MANAGE")) {
            throw new AccessDeniedException("CATALOG_MANAGE permission is required");
        }
    }

    private AdminExercise find(UUID exerciseId) {
        if (exerciseId == null) throw validation("exerciseId", "NotNull", "Exercise ID is required");
        return repository.findById(exerciseId)
                .orElseThrow(() -> new AdminExerciseNotFoundException("Exercise was not found"));
    }

    private AdminExercise findForUpdate(UUID exerciseId) {
        if (exerciseId == null) throw validation("exerciseId", "NotNull", "Exercise ID is required");
        return repository.findByIdForUpdate(exerciseId)
                .orElseThrow(() -> new AdminExerciseNotFoundException("Exercise was not found"));
    }

    private void requireStatus(AdminExercise exercise, ExerciseLifecycleStatus expected, String message) {
        if (exercise.status() != expected) throw new ExerciseLifecycleConflictException(message);
    }

    private void requireExpectedVersion(AdminExercise exercise, long expectedVersion) {
        if (exercise.version() != expectedVersion) throw versionConflict();
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion < 0) throw validation("expectedVersion", "Min", "Expected version must be zero or greater");
    }

    private ExerciseVersionConflictException versionConflict() {
        return new ExerciseVersionConflictException("Exercise was modified by another request");
    }

    private String requireReason(String reason) {
        String normalized = trimToNull(reason);
        if (normalized == null) throw validation("reason", "NotBlank", "Reason is required");
        if (normalized.length() > 1000) throw validation("reason", "Size", "Reason must not exceed 1000 characters");
        return normalized;
    }

    private void audit(UUID actor, String action, UUID targetId, String before, String after,
                       Map<String, Object> metadata, Instant now) {
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(actor)
                .actorRole("ADMIN")
                .action(action)
                .targetType("EXERCISE")
                .targetId(targetId)
                .requestId(parseRequestId())
                .beforeDataJson(before)
                .afterDataJson(after)
                .metadataJson(toJson(metadata == null ? Map.of() : metadata))
                .occurredAt(now)
                .build());
    }

    private UUID parseRequestId() {
        try {
            String requestId = RequestIdHolder.get();
            return requestId == null ? null : UUID.fromString(requestId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String snapshot(AdminExercise exercise) {
        try {
            return objectMapper.writeValueAsString(exercise);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize Exercise audit snapshot", exception);
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize Exercise audit data", exception);
        }
    }

    private List<String> normalizeCodes(List<String> values, String field, List<FieldErrorDto> errors) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            String code = upper(value);
            if (code == null) errors.add(field(field, "NotBlank", "Catalog codes must not be blank"));
            else normalized.add(code);
        }
        return List.copyOf(normalized);
    }

    private String upper(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ApplicationValidationException validation(String field, String code, String message) {
        return new ApplicationValidationException(message, List.of(field(field, code, message)));
    }

    private FieldErrorDto field(String field, String code, String message) {
        return new FieldErrorDto(field, code, message);
    }
}
