package com.fitnesscoaching.platform.modules.goal.application.service;

import com.fitnesscoaching.platform.common.exception.ActiveFitnessGoalAlreadyExistsException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FitnessGoalAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.FitnessGoalNotFoundException;
import com.fitnesscoaching.platform.common.exception.GoalVersionConflictException;
import com.fitnesscoaching.platform.common.exception.GoalVersionNotFoundException;
import com.fitnesscoaching.platform.common.exception.InvalidLifecycleTransitionException;
import com.fitnesscoaching.platform.common.exception.GoalVersionNoChangesException;
import com.fitnesscoaching.platform.common.exception.NewGoalJourneyRequiredException;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.goal.application.model.GoalVersionPage;
import com.fitnesscoaching.platform.modules.goal.application.port.in.ActivateFitnessGoalCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateFitnessGoalCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalObjectiveCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalTargetCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalVersionCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalVersionDetailQuery;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalVersionsQuery;
import com.fitnesscoaching.platform.modules.goal.application.port.out.FitnessGoalPersistencePort;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalCatalogPort;
import com.fitnesscoaching.platform.modules.goal.application.port.out.GoalStudentAuthorityPort;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoalVersion;
import com.fitnesscoaching.platform.modules.goal.domain.GoalObjective;
import com.fitnesscoaching.platform.modules.goal.domain.GoalStatus;
import com.fitnesscoaching.platform.modules.goal.domain.GoalTarget;
import com.fitnesscoaching.platform.modules.goal.domain.ObjectivePriority;
import com.fitnesscoaching.platform.modules.goal.domain.VersionLockReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FitnessGoalServiceUnitTest {

    @Mock
    private GoalStudentAuthorityPort goalStudentAuthorityPort;

    @Mock
    private GoalCatalogPort goalCatalogPort;

    @Mock
    private FitnessGoalPersistencePort fitnessGoalPersistencePort;

    @Mock
    private AuditService auditService;

    private Clock clock;
    private FitnessGoalService fitnessGoalService;

    private final UUID studentId = UUID.randomUUID();
    private final LocalDate startDate = LocalDate.of(2026, 10, 1);
    private final LocalDate targetDate = LocalDate.of(2026, 12, 31);

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC);
        fitnessGoalService = new FitnessGoalService(
                goalStudentAuthorityPort,
                goalCatalogPort,
                fitnessGoalPersistencePort,
                auditService,
                clock
        );
    }

    private void mockValidCatalogs() {
        lenient().when(goalCatalogPort.findGoalTypeById((short) 1))
                .thenReturn(Optional.of(new GoalCatalogPort.GoalTypeCatalogView((short) 1, "MUSCLE_GAIN", "Muscle Gain", true)));
        lenient().when(goalCatalogPort.findGoalTypeById((short) 2))
                .thenReturn(Optional.of(new GoalCatalogPort.GoalTypeCatalogView((short) 2, "FAT_LOSS", "Fat Loss", true)));
        lenient().when(goalCatalogPort.findGoalTypeByCode("MUSCLE_GAIN"))
                .thenReturn(Optional.of(new GoalCatalogPort.GoalTypeCatalogView((short) 1, "MUSCLE_GAIN", "Muscle Gain", true)));
        lenient().when(goalCatalogPort.findGoalTypeByCode("FAT_LOSS"))
                .thenReturn(Optional.of(new GoalCatalogPort.GoalTypeCatalogView((short) 2, "FAT_LOSS", "Fat Loss", true)));

        lenient().when(goalCatalogPort.findMetricDefinitionById(1))
                .thenReturn(Optional.of(new GoalCatalogPort.MetricDefinitionCatalogView(1, "WEIGHT", "Weight", (short) 1, "MASS", true)));
        lenient().when(goalCatalogPort.findMetricDefinitionByCode("WEIGHT"))
                .thenReturn(Optional.of(new GoalCatalogPort.MetricDefinitionCatalogView(1, "WEIGHT", "Weight", (short) 1, "MASS", true)));
        lenient().when(goalCatalogPort.findMeasurementUnitById((short) 1))
                .thenReturn(Optional.of(new GoalCatalogPort.MeasurementUnitCatalogView((short) 1, "KG", "kg", "MASS")));
        lenient().when(goalCatalogPort.findMeasurementUnitByCode("KG"))
                .thenReturn(Optional.of(new GoalCatalogPort.MeasurementUnitCatalogView((short) 1, "KG", "kg", "MASS")));
    }

    @Test
    @DisplayName("Create goal: successfully creates DRAFT goal with version, objectives, and targets")
    void createFitnessGoal_success_draft() {
        mockValidCatalogs();

        CreateFitnessGoalCommand command = new CreateFitnessGoalCommand(
                studentId,
                "Hypertrophy Season 1",
                startDate,
                targetDate,
                null,
                false,
                List.of(
                        new CreateGoalObjectiveCommand(null, "MUSCLE_GAIN", ObjectivePriority.PRIMARY, 0, "Build chest and back"),
                        new CreateGoalObjectiveCommand(null, "FAT_LOSS", ObjectivePriority.SECONDARY, 1, "Keep waist under control")
                ),
                List.of(
                        new CreateGoalTargetCommand(
                                null, "WEIGHT", null,
                                new BigDecimal("70.0"), new BigDecimal("75.0"),
                                new BigDecimal("74.0"), new BigDecimal("76.0"),
                                null, "KG", null, targetDate, "Morning scale weight"
                        )
                )
        );

        FitnessGoal expected = new FitnessGoal(
                UUID.randomUUID(),
                studentId,
                "Hypertrophy Season 1",
                GoalStatus.DRAFT,
                studentId,
                null,
                null,
                null,
                null,
                null,
                Instant.now(clock),
                Instant.now(clock),
                null
        );

        when(fitnessGoalPersistencePort.saveGoalAggregate(any(), any(), any(), any(), eq(false)))
                .thenReturn(expected);

        FitnessGoal actual = fitnessGoalService.createFitnessGoal(command);

        assertThat(actual).isEqualTo(expected);
        verify(goalStudentAuthorityPort).verifyStudentCanManageGoals(studentId);
        verify(fitnessGoalPersistencePort).saveGoalAggregate(any(), any(), any(), any(), eq(false));
        verify(auditService).recordAudit(any());
    }

    @Test
    @DisplayName("Create goal: successfully creates ACTIVE goal and locks version")
    void createFitnessGoal_success_activateImmediately() {
        mockValidCatalogs();
        when(fitnessGoalPersistencePort.hasActiveGoal(studentId)).thenReturn(false);

        CreateFitnessGoalCommand command = new CreateFitnessGoalCommand(
                studentId,
                "Immediate Lean Bulk",
                startDate,
                targetDate,
                null,
                true,
                List.of(new CreateGoalObjectiveCommand(null, "MUSCLE_GAIN", ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        FitnessGoal expected = new FitnessGoal(
                UUID.randomUUID(),
                studentId,
                "Immediate Lean Bulk",
                GoalStatus.ACTIVE,
                studentId,
                Instant.now(clock),
                null,
                null,
                null,
                null,
                Instant.now(clock),
                Instant.now(clock),
                null
        );

        when(fitnessGoalPersistencePort.saveGoalAggregate(any(), any(), any(), any(), eq(true)))
                .thenReturn(expected);

        FitnessGoal actual = fitnessGoalService.createFitnessGoal(command);

        assertThat(actual).isEqualTo(expected);
        verify(fitnessGoalPersistencePort).hasActiveGoal(studentId);
        verify(fitnessGoalPersistencePort).saveGoalAggregate(any(), any(), any(), any(), eq(true));
    }

    @Test
    @DisplayName("Create goal: throws ActiveFitnessGoalAlreadyExistsException when activateImmediately=true and active goal exists")
    void createFitnessGoal_activeGoalAlreadyExists_throwsException() {
        mockValidCatalogs();
        when(fitnessGoalPersistencePort.hasActiveGoal(studentId)).thenReturn(true);

        CreateFitnessGoalCommand command = new CreateFitnessGoalCommand(
                studentId,
                "Second Active Goal",
                startDate,
                targetDate,
                null,
                true,
                List.of(new CreateGoalObjectiveCommand(null, "MUSCLE_GAIN", ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createFitnessGoal(command))
                .isInstanceOf(ActiveFitnessGoalAlreadyExistsException.class)
                .hasMessageContaining("already has an active fitness goal");

        verify(fitnessGoalPersistencePort, never()).saveGoalAggregate(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Create goal: validation fails when title is blank")
    void createFitnessGoal_missingTitle_throwsValidationException() {
        CreateFitnessGoalCommand command = new CreateFitnessGoalCommand(
                studentId,
                "   ",
                startDate,
                targetDate,
                null,
                false,
                List.of(new CreateGoalObjectiveCommand(null, "MUSCLE_GAIN", ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createFitnessGoal(command))
                .isInstanceOf(ApplicationValidationException.class);
    }

    @Test
    @DisplayName("Create goal: validation fails when targetDate is before startDate")
    void createFitnessGoal_targetDateBeforeStartDate_throwsValidationException() {
        CreateFitnessGoalCommand command = new CreateFitnessGoalCommand(
                studentId,
                "Valid Title",
                startDate,
                startDate.minusDays(1),
                null,
                false,
                List.of(new CreateGoalObjectiveCommand(null, "MUSCLE_GAIN", ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createFitnessGoal(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Target date must be strictly after start date");
    }

    @Test
    @DisplayName("Create goal: validation fails when targetDate is equal to startDate")
    void createFitnessGoal_targetDateEqualsStartDate_throwsValidationException() {
        CreateFitnessGoalCommand command = new CreateFitnessGoalCommand(
                studentId,
                "Valid Title",
                startDate,
                startDate, // Same day
                null,
                false,
                List.of(new CreateGoalObjectiveCommand(null, "MUSCLE_GAIN", ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createFitnessGoal(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Target date must be strictly after start date");
    }

    @Test
    @DisplayName("Create goal: validation fails when durationDays does not match targetDate")
    void createFitnessGoal_targetDateAndDurationMismatch_throwsValidationException() {
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate target = LocalDate.of(2026, 10, 31); // 30 days
        Integer duration = 15; // Mismatch!

        CreateFitnessGoalCommand command = new CreateFitnessGoalCommand(
                studentId,
                "Valid Title",
                start,
                target,
                duration,
                false,
                List.of(new CreateGoalObjectiveCommand(null, "MUSCLE_GAIN", ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createFitnessGoal(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Duration days does not match target date");
    }

    @Test
    @DisplayName("Create goal: validation fails when durationDays is zero or negative")
    void createFitnessGoal_durationDaysZeroOrNegative_throwsValidationException() {
        CreateFitnessGoalCommand command = new CreateFitnessGoalCommand(
                studentId,
                "Valid Title",
                startDate,
                null,
                0, // Non-positive
                false,
                List.of(new CreateGoalObjectiveCommand(null, "MUSCLE_GAIN", ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createFitnessGoal(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Duration days must be positive");
    }

    @Test
    @DisplayName("Create goal: successfully creates goal when both targetDate and durationDays are valid and match")
    void createFitnessGoal_validTimelineBothProvided_success() {
        mockValidCatalogs();

        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate target = LocalDate.of(2026, 10, 31); // 30 days
        Integer duration = 30; // Matches!

        CreateFitnessGoalCommand command = new CreateFitnessGoalCommand(
                studentId,
                "Valid Timeline Goal",
                start,
                target,
                duration,
                false,
                List.of(new CreateGoalObjectiveCommand(null, "MUSCLE_GAIN", ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        FitnessGoal expected = new FitnessGoal(
                UUID.randomUUID(), studentId, "Valid Timeline Goal", GoalStatus.DRAFT, studentId,
                null, null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );
        when(fitnessGoalPersistencePort.saveGoalAggregate(any(), any(), any(), any(), eq(false)))
                .thenReturn(expected);

        FitnessGoal actual = fitnessGoalService.createFitnessGoal(command);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("Create goal: validation fails when no primary objective is provided")
    void createFitnessGoal_noPrimaryObjective_throwsValidationException() {
        CreateFitnessGoalCommand command = new CreateFitnessGoalCommand(
                studentId,
                "Valid Title",
                startDate,
                targetDate,
                null,
                false,
                List.of(new CreateGoalObjectiveCommand(null, "MUSCLE_GAIN", ObjectivePriority.SECONDARY, 0, null)),
                List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createFitnessGoal(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("must have exactly one PRIMARY objective");
    }

    @Test
    @DisplayName("Create goal: validation fails when multiple primary objectives are provided")
    void createFitnessGoal_multiplePrimaryObjectives_throwsValidationException() {
        CreateFitnessGoalCommand command = new CreateFitnessGoalCommand(
                studentId,
                "Valid Title",
                startDate,
                targetDate,
                null,
                false,
                List.of(
                        new CreateGoalObjectiveCommand(null, "MUSCLE_GAIN", ObjectivePriority.PRIMARY, 0, null),
                        new CreateGoalObjectiveCommand(null, "FAT_LOSS", ObjectivePriority.PRIMARY, 1, null)
                ),
                List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createFitnessGoal(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("must have exactly one PRIMARY objective");
    }

    @Test
    @DisplayName("Create goal: validation fails when duplicate goal types are in objectives")
    void createFitnessGoal_duplicateGoalType_throwsValidationException() {
        when(goalCatalogPort.findGoalTypeByCode("MUSCLE_GAIN"))
                .thenReturn(Optional.of(new GoalCatalogPort.GoalTypeCatalogView((short) 1, "MUSCLE_GAIN", "Muscle Gain", true)));

        CreateFitnessGoalCommand command = new CreateFitnessGoalCommand(
                studentId,
                "Valid Title",
                startDate,
                targetDate,
                null,
                false,
                List.of(
                        new CreateGoalObjectiveCommand(null, "MUSCLE_GAIN", ObjectivePriority.PRIMARY, 0, null),
                        new CreateGoalObjectiveCommand(null, "MUSCLE_GAIN", ObjectivePriority.SECONDARY, 1, null)
                ),
                List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createFitnessGoal(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Duplicate goal type");
    }

    @Test
    @DisplayName("Create goal: validation fails when duplicate metric definition is in targets")
    void createFitnessGoal_duplicateTargetMetric_throwsValidationException() {
        mockValidCatalogs();

        CreateFitnessGoalCommand command = new CreateFitnessGoalCommand(
                studentId,
                "Valid Title",
                startDate,
                targetDate,
                null,
                false,
                List.of(new CreateGoalObjectiveCommand(null, "MUSCLE_GAIN", ObjectivePriority.PRIMARY, 0, null)),
                List.of(
                        new CreateGoalTargetCommand(null, "WEIGHT", null, null, new BigDecimal("75.0"), null, null, null, "KG", null, null, null),
                        new CreateGoalTargetCommand(null, "WEIGHT", null, null, new BigDecimal("76.0"), null, null, null, "KG", null, null, null)
                )
        );

        assertThatThrownBy(() -> fitnessGoalService.createFitnessGoal(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Duplicate target metric definition");
    }

    @Test
    @DisplayName("Create goal: validation fails when measurement unit dimension does not match metric dimension")
    void createFitnessGoal_incompatibleUnitDimension_throwsValidationException() {
        when(goalCatalogPort.findGoalTypeByCode("MUSCLE_GAIN"))
                .thenReturn(Optional.of(new GoalCatalogPort.GoalTypeCatalogView((short) 1, "MUSCLE_GAIN", "Muscle Gain", true)));
        // Metric WEIGHT has dimension MASS
        when(goalCatalogPort.findMetricDefinitionByCode("WEIGHT"))
                .thenReturn(Optional.of(new GoalCatalogPort.MetricDefinitionCatalogView(1, "WEIGHT", "Weight", (short) 1, "MASS", true)));
        // Unit CM has dimension LENGTH
        when(goalCatalogPort.findMeasurementUnitByCode("CM"))
                .thenReturn(Optional.of(new GoalCatalogPort.MeasurementUnitCatalogView((short) 4, "CM", "cm", "LENGTH")));

        CreateFitnessGoalCommand command = new CreateFitnessGoalCommand(
                studentId,
                "Valid Title",
                startDate,
                targetDate,
                null,
                false,
                List.of(new CreateGoalObjectiveCommand(null, "MUSCLE_GAIN", ObjectivePriority.PRIMARY, 0, null)),
                List.of(
                        new CreateGoalTargetCommand(null, "WEIGHT", null, null, new BigDecimal("75.0"), null, null, null, "CM", null, null, null)
                )
        );

        assertThatThrownBy(() -> fitnessGoalService.createFitnessGoal(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("incompatible with metric dimension");
    }

    @Test
    @DisplayName("Create goal: validation fails when no target values are provided")
    void createFitnessGoal_noTargetValues_throwsValidationException() {
        mockValidCatalogs();

        CreateFitnessGoalCommand command = new CreateFitnessGoalCommand(
                studentId,
                "Valid Title",
                startDate,
                targetDate,
                null,
                false,
                List.of(new CreateGoalObjectiveCommand(null, "MUSCLE_GAIN", ObjectivePriority.PRIMARY, 0, null)),
                List.of(
                        new CreateGoalTargetCommand(null, "WEIGHT", null, new BigDecimal("70.0"), null, null, null, null, "KG", null, null, null)
                )
        );

        assertThatThrownBy(() -> fitnessGoalService.createFitnessGoal(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("At least one target value must be specified");
    }

    @Test
    @DisplayName("Get goal detail: returns goal when caller is owner")
    void getFitnessGoalDetail_success() {
        UUID goalId = UUID.randomUUID();
        FitnessGoal goal = new FitnessGoal(
                goalId, studentId, "Title", GoalStatus.DRAFT, studentId,
                null, null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(goal));

        FitnessGoal result = fitnessGoalService.getFitnessGoalDetail(studentId, goalId);

        assertThat(result).isEqualTo(goal);
        verify(goalStudentAuthorityPort).verifyStudentCanManageGoals(studentId);
    }

    @Test
    @DisplayName("Get goal detail: throws FitnessGoalNotFoundException when goal does not exist")
    void getFitnessGoalDetail_notFound_throwsException() {
        UUID goalId = UUID.randomUUID();
        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fitnessGoalService.getFitnessGoalDetail(studentId, goalId))
                .isInstanceOf(FitnessGoalNotFoundException.class);
    }

    @Test
    @DisplayName("Get goal detail: throws FitnessGoalAccessDeniedException when caller is not owner")
    void getFitnessGoalDetail_nonOwner_throwsAccessDenied() {
        UUID goalId = UUID.randomUUID();
        UUID otherStudentId = UUID.randomUUID();
        FitnessGoal goal = new FitnessGoal(
                goalId, otherStudentId, "Title", GoalStatus.DRAFT, otherStudentId,
                null, null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(goal));

        assertThatThrownBy(() -> fitnessGoalService.getFitnessGoalDetail(studentId, goalId))
                .isInstanceOf(FitnessGoalAccessDeniedException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    @DisplayName("Activate goal: successfully activates DRAFT goal")
    void activateFitnessGoal_success() {
        UUID goalId = UUID.randomUUID();
        FitnessGoal draftGoal = new FitnessGoal(
                goalId, studentId, "Draft Title", GoalStatus.DRAFT, studentId,
                null, null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );
        FitnessGoal activeGoal = new FitnessGoal(
                goalId, studentId, "Draft Title", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(draftGoal));
        when(fitnessGoalPersistencePort.hasActiveGoal(studentId)).thenReturn(false);
        when(fitnessGoalPersistencePort.activateGoal(goalId, studentId, "Ready to start")).thenReturn(activeGoal);

        FitnessGoal result = fitnessGoalService.activateFitnessGoal(new ActivateFitnessGoalCommand(studentId, goalId, "Ready to start"));

        assertThat(result.status()).isEqualTo(GoalStatus.ACTIVE);
        verify(fitnessGoalPersistencePort).activateGoal(goalId, studentId, "Ready to start");
        verify(auditService).recordAudit(any());
    }

    @Test
    @DisplayName("Activate goal: throws InvalidLifecycleTransitionException when goal is not in DRAFT")
    void activateFitnessGoal_nonDraft_throwsInvalidLifecycleTransition() {
        UUID goalId = UUID.randomUUID();
        FitnessGoal alreadyActive = new FitnessGoal(
                goalId, studentId, "Active Title", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(alreadyActive));

        assertThatThrownBy(() -> fitnessGoalService.activateFitnessGoal(new ActivateFitnessGoalCommand(studentId, goalId, null)))
                .isInstanceOf(InvalidLifecycleTransitionException.class)
                .hasMessageContaining("Cannot activate fitness goal in status: ACTIVE");
    }

    @Test
    @DisplayName("Get goal versions: successfully lists versions with pagination metadata")
    void getGoalVersions_success() {
        UUID goalId = UUID.randomUUID();
        FitnessGoal goal = new FitnessGoal(
                goalId, studentId, "Goal Title", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );
        FitnessGoalVersion v1 = new FitnessGoalVersion(
                UUID.randomUUID(), goalId, 1, "V1", startDate, targetDate, 91,
                Instant.now(clock), null, null, "INITIAL", null, studentId, null,
                Instant.now(clock), Instant.now(clock), studentId, VersionLockReason.ACTIVATED,
                List.of(), List.of()
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(goal));
        when(fitnessGoalPersistencePort.countVersionsByGoalId(goalId)).thenReturn(1L);
        when(fitnessGoalPersistencePort.findVersionsByGoalId(goalId, 20, 0)).thenReturn(List.of(v1));

        GoalVersionPage response = fitnessGoalService.getGoalVersions(new GetGoalVersionsQuery(studentId, goalId, 0, 20));

        assertThat(response.items()).hasSize(1);
        assertThat(response.totalElements()).isEqualTo(1L);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.items().get(0).effectiveUntil()).isNull();
        verify(goalStudentAuthorityPort).verifyStudentCanManageGoals(studentId);
    }

    @Test
    @DisplayName("Get goal versions: throws FitnessGoalAccessDeniedException when student is not owner")
    void getGoalVersions_nonOwner_throwsAccessDenied() {
        UUID goalId = UUID.randomUUID();
        UUID otherStudentId = UUID.randomUUID();
        FitnessGoal goal = new FitnessGoal(
                goalId, otherStudentId, "Goal Title", GoalStatus.ACTIVE, otherStudentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(goal));

        assertThatThrownBy(() -> fitnessGoalService.getGoalVersions(new GetGoalVersionsQuery(studentId, goalId, 0, 20)))
                .isInstanceOf(FitnessGoalAccessDeniedException.class);
    }

    @Test
    @DisplayName("Get goal version detail: successfully returns version detail")
    void getGoalVersionDetail_success() {
        UUID goalId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        FitnessGoal goal = new FitnessGoal(
                goalId, studentId, "Goal Title", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );
        FitnessGoalVersion v = new FitnessGoalVersion(
                versionId, goalId, 1, "V1", startDate, targetDate, 91,
                Instant.now(clock), null, null, "INITIAL", null, studentId, null,
                Instant.now(clock), Instant.now(clock), studentId, VersionLockReason.ACTIVATED,
                List.of(), List.of()
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(goal));
        when(fitnessGoalPersistencePort.findVersionById(goalId, versionId)).thenReturn(Optional.of(v));

        FitnessGoalVersion result = fitnessGoalService.getGoalVersionDetail(new GetGoalVersionDetailQuery(studentId, goalId, versionId));

        assertThat(result.id()).isEqualTo(versionId);
        assertThat(result.versionNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("Get goal version detail: throws GoalVersionNotFoundException when version not found")
    void getGoalVersionDetail_notFound_throwsNotFound() {
        UUID goalId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        FitnessGoal goal = new FitnessGoal(
                goalId, studentId, "Goal Title", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(goal));
        when(fitnessGoalPersistencePort.findVersionById(goalId, versionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fitnessGoalService.getGoalVersionDetail(new GetGoalVersionDetailQuery(studentId, goalId, versionId)))
                .isInstanceOf(GoalVersionNotFoundException.class)
                .hasMessageContaining("Goal version not found");
    }

    @Test
    @DisplayName("Create goal version: successfully creates same-journey version on ACTIVE goal")
    void createGoalVersion_success() {
        UUID goalId = UUID.randomUUID();
        UUID v1Id = UUID.randomUUID();
        FitnessGoalVersion v1 = new FitnessGoalVersion(
                v1Id, goalId, 1, "V1", startDate, targetDate, 91,
                Instant.now(clock), null, null, "INITIAL", null, studentId, null,
                Instant.now(clock), Instant.now(clock), studentId, VersionLockReason.ACTIVATED,
                List.of(), List.of()
        );
        FitnessGoal goal = new FitnessGoal(
                goalId, studentId, "Active Title", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), v1
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(goal));
        mockValidCatalogs();

        LocalDate newTargetDate = startDate.plusDays(120);
        FitnessGoalVersion v2 = new FitnessGoalVersion(
                UUID.randomUUID(), goalId, 2, "Revised Title", startDate, newTargetDate, 120,
                Instant.now(clock), null, null, "Extending duration", null, studentId, null,
                Instant.now(clock), Instant.now(clock), studentId, VersionLockReason.APPROVED,
                List.of(), List.of()
        );

        when(fitnessGoalPersistencePort.createNewGoalVersion(eq(goalId), eq(studentId), any(), eq(v1Id))).thenReturn(v2);

        CreateGoalVersionCommand command = new CreateGoalVersionCommand(
                studentId, goalId, "Revised Title", startDate, newTargetDate, 120,
                "Extending duration", "Changed from 90 to 120 days",
                List.of(new CreateGoalObjectiveCommand((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        FitnessGoalVersion created = fitnessGoalService.createGoalVersion(command);

        assertThat(created.versionNumber()).isEqualTo(2);
        assertThat(created.durationDays()).isEqualTo(120);
        verify(fitnessGoalPersistencePort).createNewGoalVersion(eq(goalId), eq(studentId), any(), eq(v1Id));
        verify(auditService).recordAudit(any());
    }

    @Test
    @DisplayName("Create goal version: throws InvalidLifecycleTransitionException when goal is not ACTIVE")
    void createGoalVersion_nonActive_throwsInvalidTransition() {
        UUID goalId = UUID.randomUUID();
        FitnessGoal draftGoal = new FitnessGoal(
                goalId, studentId, "Draft Title", GoalStatus.DRAFT, studentId,
                null, null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(draftGoal));

        CreateGoalVersionCommand command = new CreateGoalVersionCommand(
                studentId, goalId, "Title", startDate, targetDate, 91,
                "Reason", null, List.of(), List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createGoalVersion(command))
                .isInstanceOf(InvalidLifecycleTransitionException.class)
                .hasMessageContaining("Goal versions can only be created for an ACTIVE fitness goal");
    }

    @Test
    @DisplayName("Create goal version: propagates GoalVersionConflictException on CAS race")
    void createGoalVersion_casConflict_propagatesConflictException() {
        UUID goalId = UUID.randomUUID();
        UUID v1Id = UUID.randomUUID();
        FitnessGoalVersion v1 = new FitnessGoalVersion(
                v1Id, goalId, 1, "V1", startDate, targetDate, 91,
                Instant.now(clock), null, null, "INITIAL", null, studentId, null,
                Instant.now(clock), Instant.now(clock), studentId, VersionLockReason.ACTIVATED,
                List.of(), List.of()
        );
        FitnessGoal goal = new FitnessGoal(
                goalId, studentId, "Active Title", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), v1
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(goal));
        mockValidCatalogs();
        when(fitnessGoalPersistencePort.createNewGoalVersion(any(), any(), any(), any()))
                .thenThrow(new GoalVersionConflictException("Current goal version has changed; unable to create version"));

        CreateGoalVersionCommand command = new CreateGoalVersionCommand(
                studentId, goalId, "Title", startDate, targetDate, 91,
                "Reason", null,
                List.of(new CreateGoalObjectiveCommand((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createGoalVersion(command))
                .isInstanceOf(GoalVersionConflictException.class);
    }

    @Test
    @DisplayName("Create goal version: throws NewGoalJourneyRequiredException when changing PRIMARY goal type")
    void createGoalVersion_changePrimaryGoalType_throwsNewGoalJourneyRequired() {
        UUID goalId = UUID.randomUUID();
        UUID v1Id = UUID.randomUUID();
        GoalObjective currentPrimary = new GoalObjective(
                UUID.randomUUID(), v1Id, (short) 1, "MUSCLE_GAIN", "Muscle Gain",
                ObjectivePriority.PRIMARY, 0, null
        );
        FitnessGoalVersion v1 = new FitnessGoalVersion(
                v1Id, goalId, 1, "V1", startDate, targetDate, 91,
                Instant.now(clock), null, null, "INITIAL", null, studentId, null,
                Instant.now(clock), Instant.now(clock), studentId, VersionLockReason.ACTIVATED,
                List.of(currentPrimary), List.of()
        );
        FitnessGoal goal = new FitnessGoal(
                goalId, studentId, "Active Title", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), v1
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(goal));
        mockValidCatalogs();

        // Attempting to change PRIMARY to FAT_LOSS (short id 2)
        CreateGoalVersionCommand command = new CreateGoalVersionCommand(
                studentId, goalId, "Shift to fat loss", startDate, targetDate.plusDays(30), 121,
                "Changing strategy", null,
                List.of(new CreateGoalObjectiveCommand((short) 2, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createGoalVersion(command))
                .isInstanceOf(NewGoalJourneyRequiredException.class)
                .hasMessageContaining("Primary goal type cannot be changed within the same journey");
    }

    @Test
    @DisplayName("Create goal version: succeeds when keeping same PRIMARY goal type and altering secondary objectives")
    void createGoalVersion_samePrimaryWithSecondaryChanges_succeeds() {
        UUID goalId = UUID.randomUUID();
        UUID v1Id = UUID.randomUUID();
        GoalObjective currentPrimary = new GoalObjective(
                UUID.randomUUID(), v1Id, (short) 1, "MUSCLE_GAIN", "Muscle Gain",
                ObjectivePriority.PRIMARY, 0, null
        );
        FitnessGoalVersion v1 = new FitnessGoalVersion(
                v1Id, goalId, 1, "V1", startDate, targetDate, 91,
                Instant.now(clock), null, null, "INITIAL", null, studentId, null,
                Instant.now(clock), Instant.now(clock), studentId, VersionLockReason.ACTIVATED,
                List.of(currentPrimary), List.of()
        );
        FitnessGoal goal = new FitnessGoal(
                goalId, studentId, "Active Title", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), v1
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(goal));
        mockValidCatalogs();

        FitnessGoalVersion v2 = new FitnessGoalVersion(
                UUID.randomUUID(), goalId, 2, "V2 with secondary", startDate, targetDate, 91,
                Instant.now(clock), null, null, "Added secondary objective", null, studentId, null,
                Instant.now(clock), Instant.now(clock), studentId, VersionLockReason.APPROVED,
                List.of(), List.of()
        );
        when(fitnessGoalPersistencePort.createNewGoalVersion(eq(goalId), eq(studentId), any(), eq(v1Id))).thenReturn(v2);

        // Keep MUSCLE_GAIN (id 1) as PRIMARY, add FAT_LOSS (id 2) as SECONDARY
        CreateGoalVersionCommand command = new CreateGoalVersionCommand(
                studentId, goalId, "V2 with secondary", startDate, targetDate, 91,
                "Added secondary objective", null,
                List.of(
                        new CreateGoalObjectiveCommand((short) 1, null, ObjectivePriority.PRIMARY, 0, null),
                        new CreateGoalObjectiveCommand((short) 2, null, ObjectivePriority.SECONDARY, 1, "Keep fat low")
                ),
                List.of()
        );

        FitnessGoalVersion created = fitnessGoalService.createGoalVersion(command);
        assertThat(created.versionNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("Create goal version: throws GoalVersionNoChangesException when no differences detected")
    void createGoalVersion_noChangesDetected_throwsNoChangesException() {
        UUID goalId = UUID.randomUUID();
        UUID v1Id = UUID.randomUUID();
        GoalObjective currentPrimary = new GoalObjective(
                UUID.randomUUID(), v1Id, (short) 1, "MUSCLE_GAIN", "Muscle Gain",
                ObjectivePriority.PRIMARY, 0, null
        );
        FitnessGoalVersion v1 = new FitnessGoalVersion(
                v1Id, goalId, 1, "Active Title", startDate, targetDate, 91,
                Instant.now(clock), null, null, "INITIAL", null, studentId, null,
                Instant.now(clock), Instant.now(clock), studentId, VersionLockReason.ACTIVATED,
                List.of(currentPrimary), List.of()
        );
        FitnessGoal goal = new FitnessGoal(
                goalId, studentId, "Active Title", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), v1
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(goal));
        mockValidCatalogs();

        // Exact same title, timeline, objectives, targets
        CreateGoalVersionCommand command = new CreateGoalVersionCommand(
                studentId, goalId, "Active Title", startDate, targetDate, 91,
                "Just checking", null,
                List.of(new CreateGoalObjectiveCommand((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createGoalVersion(command))
                .isInstanceOf(GoalVersionNoChangesException.class)
                .hasMessageContaining("no changes from the current active version");
    }

    @Test
    @DisplayName("Create goal version: throws ApplicationValidationException when changeReason exceeds 100 characters")
    void createGoalVersion_reasonExceeds100Chars_throwsValidationException() {
        UUID goalId = UUID.randomUUID();
        FitnessGoal goal = new FitnessGoal(
                goalId, studentId, "Active Title", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock),
                new FitnessGoalVersion(UUID.randomUUID(), goalId, 1, "V1", startDate, targetDate, 91,
                        Instant.now(clock), null, null, "INITIAL", null, studentId, null,
                        Instant.now(clock), Instant.now(clock), studentId, VersionLockReason.ACTIVATED, List.of(), List.of())
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(goal));

        String longReason = "R".repeat(101);
        CreateGoalVersionCommand command = new CreateGoalVersionCommand(
                studentId, goalId, "Title", startDate, targetDate, 91,
                longReason, null, List.of(), List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createGoalVersion(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Change reason must not exceed 100 characters");
    }

    @Test
    @DisplayName("Create goal version: throws ApplicationValidationException on invalid target values")
    void createGoalVersion_invalidTargetValues_throwsValidationException() {
        UUID goalId = UUID.randomUUID();
        UUID v1Id = UUID.randomUUID();
        GoalObjective currentPrimary = new GoalObjective(
                UUID.randomUUID(), v1Id, (short) 1, "MUSCLE_GAIN", "Muscle Gain",
                ObjectivePriority.PRIMARY, 0, null
        );
        FitnessGoalVersion v1 = new FitnessGoalVersion(
                v1Id, goalId, 1, "V1", startDate, targetDate, 91,
                Instant.now(clock), null, null, "INITIAL", null, studentId, null,
                Instant.now(clock), Instant.now(clock), studentId, VersionLockReason.ACTIVATED,
                List.of(currentPrimary), List.of()
        );
        FitnessGoal goal = new FitnessGoal(
                goalId, studentId, "Active Title", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), v1
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(goal));
        mockValidCatalogs();

        // Target min > target max
        CreateGoalVersionCommand command = new CreateGoalVersionCommand(
                studentId, goalId, "Revised", startDate, targetDate.plusDays(30), 121,
                "Target adjustment", null,
                List.of(new CreateGoalObjectiveCommand((short) 1, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of(new CreateGoalTargetCommand(
                        1, null, null,
                        BigDecimal.valueOf(70), BigDecimal.valueOf(80),
                        BigDecimal.valueOf(85), BigDecimal.valueOf(75), // min > max
                        (short) 1, null, 10, null, null
                ))
        );

        assertThatThrownBy(() -> fitnessGoalService.createGoalVersion(command))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Target max value cannot be less than min value");
    }
}
