package com.fitnesscoaching.platform.modules.exercise.application.service;

import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.ExerciseCanonicalConflictException;
import com.fitnesscoaching.platform.common.exception.ExerciseLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.ExerciseVersionConflictException;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseDraftData;
import com.fitnesscoaching.platform.modules.exercise.application.model.AdminExerciseFormMetadata;
import com.fitnesscoaching.platform.modules.exercise.application.port.out.AdminExerciseRepository;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExercise;
import com.fitnesscoaching.platform.modules.exercise.domain.AdminExerciseVariation;
import com.fitnesscoaching.platform.modules.exercise.domain.CatalogOption;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseLifecycleStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserPermissionQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminExerciseCatalogServiceUnitTest {

    private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID EXERCISE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID TARGET = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

    @Mock private AdminExerciseRepository repository;
    @Mock private UserAccountStatusQuery accountStatusQuery;
    @Mock private UserRoleQuery roleQuery;
    @Mock private UserPermissionQuery permissionQuery;
    @Mock private AuditService auditService;

    private AdminExerciseCatalogService service;

    @BeforeEach
    void setUp() {
        service = new AdminExerciseCatalogService(repository, accountStatusQuery, roleQuery, permissionQuery,
                auditService, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createAlwaysPersistsDraftAndAudits() {
        authorize();
        ExerciseDraftData draft = completeDraft();
        when(repository.findDraftReferenceProblems(any())).thenReturn(List.of());
        when(repository.createDraft(any(), eq(ADMIN), any(), eq(NOW)))
                .thenAnswer(invocation -> exercise(invocation.getArgument(0), ExerciseLifecycleStatus.DRAFT, 0, null));

        AdminExercise created = service.createDraft(ADMIN, draft);

        assertThat(created.status()).isEqualTo(ExerciseLifecycleStatus.DRAFT);
        verify(auditService).recordAudit(any());
    }

    @Test
    void draftCanBeEditedWithExpectedVersion() {
        authorize();
        when(repository.findByIdForUpdate(EXERCISE)).thenReturn(Optional.of(exercise(EXERCISE, ExerciseLifecycleStatus.DRAFT, 2, null)));
        when(repository.findDraftReferenceProblems(any())).thenReturn(List.of());
        when(repository.updateDraft(eq(EXERCISE), eq(2L), any(), eq(NOW))).thenReturn(true);
        when(repository.findById(EXERCISE)).thenReturn(Optional.of(exercise(EXERCISE, ExerciseLifecycleStatus.DRAFT, 3, null)));

        AdminExercise result = service.updateDraft(ADMIN, EXERCISE, 2, completeDraft());

        assertThat(result.version()).isEqualTo(3);
        verify(auditService).recordAudit(any());
    }

    @Test
    void activeAndArchivedExercisesCannotBeEdited() {
        authorize();
        for (ExerciseLifecycleStatus status : List.of(ExerciseLifecycleStatus.ACTIVE, ExerciseLifecycleStatus.ARCHIVED)) {
            when(repository.findByIdForUpdate(EXERCISE)).thenReturn(Optional.of(exercise(EXERCISE, status, 1, null)));
            assertThatThrownBy(() -> service.updateDraft(ADMIN, EXERCISE, 1, completeDraft()))
                    .isInstanceOf(ExerciseLifecycleConflictException.class);
        }
        verify(repository, never()).updateDraft(any(), anyLong(), any(), any());
    }

    @Test
    void validDraftActivatesAtomicallyAndAudits() {
        authorize();
        when(repository.findByIdForUpdate(EXERCISE)).thenReturn(Optional.of(exercise(EXERCISE, ExerciseLifecycleStatus.DRAFT, 0, null)));
        when(repository.findActivationProblems(EXERCISE)).thenReturn(List.of());
        when(repository.transition(EXERCISE, 0, ExerciseLifecycleStatus.DRAFT, ExerciseLifecycleStatus.ACTIVE, NOW)).thenReturn(true);
        when(repository.findById(EXERCISE)).thenReturn(Optional.of(exercise(EXERCISE, ExerciseLifecycleStatus.ACTIVE, 1, null)));

        assertThat(service.activate(ADMIN, EXERCISE, 0).status()).isEqualTo(ExerciseLifecycleStatus.ACTIVE);
        verify(auditService).recordAudit(any());
    }

    @Test
    void activationRejectsMissingRequiredContentAndInactiveMetadata() {
        authorize();
        AdminExercise incomplete = new AdminExercise(EXERCISE, "CODE", "Name", null, null, null,
                null, null, false, ExerciseLifecycleStatus.DRAFT, 0, null, List.of(), List.of(), NOW, NOW);
        when(repository.findByIdForUpdate(EXERCISE)).thenReturn(Optional.of(incomplete));
        when(repository.findActivationProblems(EXERCISE)).thenReturn(List.of("equipmentCodes"));

        assertThatThrownBy(() -> service.activate(ADMIN, EXERCISE, 0))
                .isInstanceOf(ApplicationValidationException.class)
                .satisfies(error -> assertThat(((ApplicationValidationException) error).getFieldErrors()).hasSize(4));
        verify(repository, never()).transition(any(), anyLong(), any(), any(), any());
    }

    @Test
    void activeCanArchiveButDraftAndArchivedCannot() {
        authorize();
        when(repository.findByIdForUpdate(EXERCISE)).thenReturn(Optional.of(exercise(EXERCISE, ExerciseLifecycleStatus.ACTIVE, 4, null)));
        when(repository.isCanonicalTarget(EXERCISE)).thenReturn(false);
        when(repository.transition(EXERCISE, 4, ExerciseLifecycleStatus.ACTIVE, ExerciseLifecycleStatus.ARCHIVED, NOW)).thenReturn(true);
        when(repository.findById(EXERCISE)).thenReturn(Optional.of(exercise(EXERCISE, ExerciseLifecycleStatus.ARCHIVED, 5, null)));
        assertThat(service.archive(ADMIN, EXERCISE, 4, "Duplicate").status()).isEqualTo(ExerciseLifecycleStatus.ARCHIVED);

        for (ExerciseLifecycleStatus status : List.of(ExerciseLifecycleStatus.DRAFT, ExerciseLifecycleStatus.ARCHIVED)) {
            when(repository.findByIdForUpdate(EXERCISE)).thenReturn(Optional.of(exercise(EXERCISE, status, 5, null)));
            assertThatThrownBy(() -> service.archive(ADMIN, EXERCISE, 5, "No"))
                    .isInstanceOf(ExerciseLifecycleConflictException.class);
        }
    }

    @Test
    void archivedCannotBeActivatedAgain() {
        authorize();
        when(repository.findByIdForUpdate(EXERCISE)).thenReturn(Optional.of(exercise(EXERCISE, ExerciseLifecycleStatus.ARCHIVED, 1, null)));
        assertThatThrownBy(() -> service.activate(ADMIN, EXERCISE, 1))
                .isInstanceOf(ExerciseLifecycleConflictException.class);
    }

    @Test
    void staleExpectedVersionFailsBeforeMutation() {
        authorize();
        when(repository.findByIdForUpdate(EXERCISE)).thenReturn(Optional.of(exercise(EXERCISE, ExerciseLifecycleStatus.DRAFT, 3, null)));
        assertThatThrownBy(() -> service.updateDraft(ADMIN, EXERCISE, 2, completeDraft()))
                .isInstanceOf(ExerciseVersionConflictException.class);
        verify(repository, never()).updateDraft(any(), anyLong(), any(), any());
    }

    @Test
    void archivedToActiveCanonicalMappingSucceedsAndCanChangeOrClear() {
        authorize();
        AdminExercise source = exercise(EXERCISE, ExerciseLifecycleStatus.ARCHIVED, 2, null);
        when(repository.findByIdForUpdate(EXERCISE)).thenReturn(Optional.of(source));
        when(repository.findByIdForUpdate(TARGET)).thenReturn(Optional.of(exercise(TARGET, ExerciseLifecycleStatus.ACTIVE, 1, null)));
        when(repository.hasCanonicalReplacement(TARGET)).thenReturn(false);
        when(repository.isCanonicalTarget(EXERCISE)).thenReturn(false);
        when(repository.setCanonicalReplacement(EXERCISE, 2, TARGET, ADMIN, "Replacement", NOW)).thenReturn(true);
        when(repository.findById(EXERCISE)).thenReturn(Optional.of(exercise(EXERCISE, ExerciseLifecycleStatus.ARCHIVED, 3, TARGET)));

        assertThat(service.setCanonicalReplacement(ADMIN, EXERCISE, 2, TARGET, "Replacement")
                .canonicalReplacementId()).isEqualTo(TARGET);

        when(repository.findByIdForUpdate(EXERCISE)).thenReturn(Optional.of(exercise(EXERCISE, ExerciseLifecycleStatus.ARCHIVED, 3, TARGET)));
        when(repository.clearCanonicalReplacement(EXERCISE, 3, NOW)).thenReturn(true);
        when(repository.findById(EXERCISE)).thenReturn(Optional.of(exercise(EXERCISE, ExerciseLifecycleStatus.ARCHIVED, 4, null)));
        assertThat(service.setCanonicalReplacement(ADMIN, EXERCISE, 3, null, "No longer needed")
                .canonicalReplacementId()).isNull();
    }

    @Test
    void canonicalMappingRejectsSelfDraftArchivedDeletedEquivalentChainAndCycle() {
        authorize();
        when(repository.findByIdForUpdate(EXERCISE)).thenReturn(Optional.of(exercise(EXERCISE, ExerciseLifecycleStatus.ARCHIVED, 0, null)));
        assertThatThrownBy(() -> service.setCanonicalReplacement(ADMIN, EXERCISE, 0, EXERCISE, "Self"))
                .isInstanceOf(ExerciseCanonicalConflictException.class);

        for (ExerciseLifecycleStatus invalid : List.of(ExerciseLifecycleStatus.DRAFT, ExerciseLifecycleStatus.ARCHIVED)) {
            when(repository.findByIdForUpdate(TARGET)).thenReturn(Optional.of(exercise(TARGET, invalid, 0, null)));
            assertThatThrownBy(() -> service.setCanonicalReplacement(ADMIN, EXERCISE, 0, TARGET, "Invalid"))
                    .isInstanceOf(ExerciseCanonicalConflictException.class);
        }

        when(repository.findByIdForUpdate(TARGET)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setCanonicalReplacement(ADMIN, EXERCISE, 0, TARGET, "Deleted"))
                .isInstanceOf(com.fitnesscoaching.platform.common.exception.AdminExerciseNotFoundException.class);

        when(repository.findByIdForUpdate(TARGET)).thenReturn(Optional.of(exercise(TARGET, ExerciseLifecycleStatus.ACTIVE, 0, UUID.randomUUID())));
        assertThatThrownBy(() -> service.setCanonicalReplacement(ADMIN, EXERCISE, 0, TARGET, "Chain"))
                .isInstanceOf(ExerciseCanonicalConflictException.class);

        when(repository.findByIdForUpdate(TARGET)).thenReturn(Optional.of(exercise(TARGET, ExerciseLifecycleStatus.ACTIVE, 0, null)));
        when(repository.hasCanonicalReplacement(TARGET)).thenReturn(false);
        when(repository.isCanonicalTarget(EXERCISE)).thenReturn(true);
        assertThatThrownBy(() -> service.setCanonicalReplacement(ADMIN, EXERCISE, 0, TARGET, "Cycle"))
                .isInstanceOf(ExerciseCanonicalConflictException.class);
    }

    @Test
    void concurrentCanonicalUpdateReturnsStableVersionConflict() {
        authorize();
        when(repository.findByIdForUpdate(EXERCISE)).thenReturn(Optional.of(exercise(EXERCISE, ExerciseLifecycleStatus.ARCHIVED, 1, null)));
        when(repository.findByIdForUpdate(TARGET)).thenReturn(Optional.of(exercise(TARGET, ExerciseLifecycleStatus.ACTIVE, 0, null)));
        when(repository.hasCanonicalReplacement(TARGET)).thenReturn(false);
        when(repository.isCanonicalTarget(EXERCISE)).thenReturn(false);
        when(repository.setCanonicalReplacement(EXERCISE, 1, TARGET, ADMIN, "Replacement", NOW)).thenReturn(false);

        assertThatThrownBy(() -> service.setCanonicalReplacement(ADMIN, EXERCISE, 1, TARGET, "Replacement"))
                .isInstanceOf(ExerciseVersionConflictException.class);
        verify(auditService, never()).recordAudit(any());
    }

    @Test
    void adminWithoutPermissionIsDeniedBeforeResourceLookup() {
        when(accountStatusQuery.getAccountStatus(ADMIN)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(roleQuery.hasActiveRole(ADMIN, "ADMIN")).thenReturn(true);
        when(permissionQuery.hasActivePermission(ADMIN, "CATALOG_MANAGE")).thenReturn(false);

        assertThatThrownBy(() -> service.getDetail(ADMIN, EXERCISE)).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).findById(any());
    }

    @Test
    void metadataRequiresEffectivePermissionBeforeRepositoryAccess() {
        when(accountStatusQuery.getAccountStatus(ADMIN)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(roleQuery.hasActiveRole(ADMIN, "ADMIN")).thenReturn(true);
        when(permissionQuery.hasActivePermission(ADMIN, "CATALOG_MANAGE")).thenReturn(false);

        assertThatThrownBy(() -> service.getFormMetadata(ADMIN)).isInstanceOf(AccessDeniedException.class);

        verify(repository, never()).findFormMetadata();
    }

    @Test
    void metadataUsesDomainDifficultyOrderAndRepositoryCatalogs() {
        authorize();
        CatalogOption squat = new CatalogOption("SQUAT", "Squat");
        when(repository.findFormMetadata()).thenReturn(new AdminExerciseFormMetadata(
                List.of(), List.of(), List.of(), List.of(), List.of("WRONG"), List.of(squat)));

        AdminExerciseFormMetadata metadata = service.getFormMetadata(ADMIN);

        assertThat(metadata.difficulties()).containsExactly("BEGINNER", "INTERMEDIATE", "ADVANCED");
        assertThat(metadata.movementPatterns()).containsExactly(squat);
    }

    private void authorize() {
        when(accountStatusQuery.getAccountStatus(ADMIN)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(roleQuery.hasActiveRole(ADMIN, "ADMIN")).thenReturn(true);
        when(permissionQuery.hasActivePermission(ADMIN, "CATALOG_MANAGE")).thenReturn(true);
    }

    private ExerciseDraftData completeDraft() {
        return new ExerciseDraftData("TEST_EXERCISE", "Test Exercise", "STRENGTH", "Description",
                "Instructions", "BEGINNER", "SQUAT", false, List.of(),
                List.of(new AdminExerciseVariation(null, "TEST_STANDARD", "Standard", null, null,
                        "BEGINNER", true, true, List.of(), List.of())));
    }

    private AdminExercise exercise(UUID id, ExerciseLifecycleStatus status, long version, UUID replacement) {
        return new AdminExercise(id, "TEST_EXERCISE", "Test Exercise", "STRENGTH", "Description",
                "Instructions", "BEGINNER", "SQUAT", false, status, version, replacement, List.of(),
                List.of(new AdminExerciseVariation(UUID.randomUUID(), "TEST_STANDARD", "Standard", null,
                        null, "BEGINNER", true, true, List.of(), List.of())), NOW, NOW);
    }
}
