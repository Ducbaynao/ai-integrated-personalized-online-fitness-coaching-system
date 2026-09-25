package com.fitnesscoaching.platform.modules.goal.application.service;

import com.fitnesscoaching.platform.common.exception.ActiveFitnessGoalAlreadyExistsException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FitnessGoalAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.FitnessGoalNotFoundException;
import com.fitnesscoaching.platform.common.exception.GoalLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.GoalVersionConflictException;
import com.fitnesscoaching.platform.common.exception.GoalVersionNotFoundException;
import com.fitnesscoaching.platform.common.exception.InvalidLifecycleTransitionException;
import com.fitnesscoaching.platform.common.exception.GoalTransitionNotFoundException;
import com.fitnesscoaching.platform.common.exception.GoalVersionNoChangesException;
import com.fitnesscoaching.platform.common.exception.NewGoalJourneyRequiredException;
import com.fitnesscoaching.platform.common.exception.SameGoalJourneyTransitionException;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.goal.application.model.GoalTransitionResult;
import com.fitnesscoaching.platform.modules.goal.application.model.GoalVersionPage;
import com.fitnesscoaching.platform.modules.goal.application.port.in.ActivateFitnessGoalCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateFitnessGoalCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalObjectiveCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalTargetCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalTransitionCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.CreateGoalVersionCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalTransitionDetailQuery;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalTransitionsQuery;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalVersionDetailQuery;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetGoalVersionsQuery;
import com.fitnesscoaching.platform.modules.goal.application.port.in.PauseFitnessGoalCommand;
import com.fitnesscoaching.platform.modules.goal.application.port.in.ResumeFitnessGoalCommand;
import com.fitnesscoaching.platform.modules.goal.domain.GoalTransition;
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

    @Test
    @DisplayName("Create goal transition: success replaces previous goal and activates new goal")
    void createGoalTransition_success_replacesGoalAndActivatesNew() {
        UUID previousGoalId = UUID.randomUUID();
        UUID v1Id = UUID.randomUUID();
        GoalObjective currentPrimary = new GoalObjective(
                UUID.randomUUID(), v1Id, (short) 1, "MUSCLE_GAIN", "Muscle Gain",
                ObjectivePriority.PRIMARY, 0, null
        );
        FitnessGoalVersion v1 = new FitnessGoalVersion(
                v1Id, previousGoalId, 1, "V1", startDate, targetDate, 91,
                Instant.now(clock), null, null, "INITIAL", null, studentId, null,
                Instant.now(clock), Instant.now(clock), studentId, VersionLockReason.ACTIVATED,
                List.of(currentPrimary), List.of()
        );
        FitnessGoal previousGoal = new FitnessGoal(
                previousGoalId, studentId, "Old Journey", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), v1
        );

        when(fitnessGoalPersistencePort.findById(previousGoalId)).thenReturn(Optional.of(previousGoal));
        mockValidCatalogs();

        UUID transitionId = UUID.randomUUID();
        UUID newGoalId = UUID.randomUUID();
        GoalTransition transition = new GoalTransition(
                transitionId, previousGoalId, newGoalId, "Switching to Fat Loss", null, studentId, Instant.now(clock), "Notes"
        );
        FitnessGoal newGoal = new FitnessGoal(
                newGoalId, studentId, "Fat Loss Journey", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );
        GoalTransitionResult expectedResult = new GoalTransitionResult(transition, newGoal);

        when(fitnessGoalPersistencePort.createDirectGoalTransition(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(expectedResult);

        CreateGoalTransitionCommand command = new CreateGoalTransitionCommand(
                studentId, previousGoalId, "Switching to Fat Loss", "Notes", "Fat Loss Journey",
                startDate, targetDate, 91,
                List.of(new CreateGoalObjectiveCommand((short) 2, "FAT_LOSS", ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        GoalTransitionResult actual = fitnessGoalService.createGoalTransition(command);

        assertThat(actual).isNotNull();
        assertThat(actual.transition().id()).isEqualTo(transitionId);
        assertThat(actual.transition().previousGoalId()).isEqualTo(previousGoalId);
        assertThat(actual.transition().newGoalId()).isEqualTo(newGoalId);
        verify(goalStudentAuthorityPort).verifyStudentCanManageGoals(studentId);
        verify(fitnessGoalPersistencePort).createDirectGoalTransition(eq(previousGoalId), eq(studentId), eq("Switching to Fat Loss"), eq("Notes"), any(), any(), any(), any());
        verify(auditService).recordAudit(argThat(r -> r.action().equals("FITNESS_GOAL_REPLACED")));
        verify(auditService).recordAudit(argThat(r -> r.action().equals("FITNESS_GOAL_CREATED")));
        verify(auditService).recordAudit(argThat(r -> r.action().equals("FITNESS_GOAL_ACTIVATED")));
        verify(auditService).recordAudit(argThat(r -> r.action().equals("GOAL_TRANSITION_CREATED")));
    }

    @Test
    @DisplayName("Create goal transition: throws SameGoalJourneyTransitionException if primary goal type is unchanged")
    void createGoalTransition_samePrimaryType_throwsSameGoalJourneyException() {
        UUID previousGoalId = UUID.randomUUID();
        UUID v1Id = UUID.randomUUID();
        GoalObjective currentPrimary = new GoalObjective(
                UUID.randomUUID(), v1Id, (short) 1, "MUSCLE_GAIN", "Muscle Gain",
                ObjectivePriority.PRIMARY, 0, null
        );
        FitnessGoalVersion v1 = new FitnessGoalVersion(
                v1Id, previousGoalId, 1, "V1", startDate, targetDate, 91,
                Instant.now(clock), null, null, "INITIAL", null, studentId, null,
                Instant.now(clock), Instant.now(clock), studentId, VersionLockReason.ACTIVATED,
                List.of(currentPrimary), List.of()
        );
        FitnessGoal previousGoal = new FitnessGoal(
                previousGoalId, studentId, "Old Journey", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), v1
        );

        when(fitnessGoalPersistencePort.findById(previousGoalId)).thenReturn(Optional.of(previousGoal));
        mockValidCatalogs();

        CreateGoalTransitionCommand command = new CreateGoalTransitionCommand(
                studentId, previousGoalId, "Switching", "Notes", "Same Primary",
                startDate, targetDate, 91,
                List.of(new CreateGoalObjectiveCommand((short) 1, "MUSCLE_GAIN", ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createGoalTransition(command))
                .isInstanceOf(SameGoalJourneyTransitionException.class)
                .hasMessageContaining("Cannot transition to a new journey with the same primary goal type; use goal versioning instead");
    }

    @Test
    @DisplayName("Create goal transition: throws InvalidLifecycleTransitionException if previous goal is not ACTIVE")
    void createGoalTransition_nonActiveGoal_throwsInvalidLifecycleTransitionException() {
        UUID previousGoalId = UUID.randomUUID();
        FitnessGoal previousGoal = new FitnessGoal(
                previousGoalId, studentId, "Paused Goal", GoalStatus.PAUSED, studentId,
                Instant.now(clock), Instant.now(clock), null, null, null, Instant.now(clock), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(previousGoalId)).thenReturn(Optional.of(previousGoal));

        CreateGoalTransitionCommand command = new CreateGoalTransitionCommand(
                studentId, previousGoalId, "Switching", null, "New Journey",
                startDate, targetDate, 91,
                List.of(new CreateGoalObjectiveCommand((short) 2, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createGoalTransition(command))
                .isInstanceOf(InvalidLifecycleTransitionException.class)
                .hasMessageContaining("Cannot transition a goal that is not ACTIVE");
    }

    @Test
    @DisplayName("Create goal transition: throws FitnessGoalAccessDeniedException if student is not owner")
    void createGoalTransition_notOwner_throwsAccessDenied() {
        UUID previousGoalId = UUID.randomUUID();
        UUID otherStudentId = UUID.randomUUID();
        FitnessGoal previousGoal = new FitnessGoal(
                previousGoalId, otherStudentId, "Other Student Goal", GoalStatus.ACTIVE, otherStudentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(previousGoalId)).thenReturn(Optional.of(previousGoal));

        CreateGoalTransitionCommand command = new CreateGoalTransitionCommand(
                studentId, previousGoalId, "Switching", null, "New Journey",
                startDate, targetDate, 91,
                List.of(new CreateGoalObjectiveCommand((short) 2, null, ObjectivePriority.PRIMARY, 0, null)),
                List.of()
        );

        assertThatThrownBy(() -> fitnessGoalService.createGoalTransition(command))
                .isInstanceOf(FitnessGoalAccessDeniedException.class)
                .hasMessageContaining("Student does not own this fitness goal");
    }

    @Test
    @DisplayName("Get goal transitions: returns transitions list for owner")
    void getGoalTransitions_success_returnsList() {
        UUID goalId = UUID.randomUUID();
        FitnessGoal goal = new FitnessGoal(
                goalId, studentId, "Goal", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );
        GoalTransition transition = new GoalTransition(
                UUID.randomUUID(), goalId, UUID.randomUUID(), "Reason", null, studentId, Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(goal));
        when(fitnessGoalPersistencePort.findTransitionsByGoalId(goalId)).thenReturn(List.of(transition));

        List<GoalTransition> actual = fitnessGoalService.getGoalTransitions(new GetGoalTransitionsQuery(studentId, goalId));

        assertThat(actual).hasSize(1);
        assertThat(actual.get(0).id()).isEqualTo(transition.id());
    }

    @Test
    @DisplayName("Get goal transition detail: returns transition detail for owner")
    void getGoalTransitionDetail_success_returnsDetail() {
        UUID goalId = UUID.randomUUID();
        UUID transitionId = UUID.randomUUID();
        FitnessGoal goal = new FitnessGoal(
                goalId, studentId, "Goal", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );
        GoalTransition transition = new GoalTransition(
                transitionId, goalId, UUID.randomUUID(), "Reason", null, studentId, Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(goal));
        when(fitnessGoalPersistencePort.findTransitionById(transitionId)).thenReturn(Optional.of(transition));

        GoalTransition actual = fitnessGoalService.getGoalTransitionDetail(new GetGoalTransitionDetailQuery(studentId, goalId, transitionId));

        assertThat(actual).isNotNull();
        assertThat(actual.id()).isEqualTo(transitionId);
    }

    @Test
    @DisplayName("Get goal transition detail: throws GoalTransitionNotFoundException if transition does not exist")
    void getGoalTransitionDetail_notFound_throwsNotFound() {
        UUID goalId = UUID.randomUUID();
        UUID transitionId = UUID.randomUUID();
        FitnessGoal goal = new FitnessGoal(
                goalId, studentId, "Goal", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(goal));
        when(fitnessGoalPersistencePort.findTransitionById(transitionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fitnessGoalService.getGoalTransitionDetail(new GetGoalTransitionDetailQuery(studentId, goalId, transitionId)))
                .isInstanceOf(GoalTransitionNotFoundException.class)
                .hasMessageContaining("Goal transition not found");
    }

    // ==========================================
    // GOAL-05: Pause and Resume Unit Tests
    // ==========================================

    @Test
    @DisplayName("Pause goal: success pauses active goal, sets paused_at, status_reason, and records audit")
    void pauseFitnessGoal_success() {
        UUID goalId = UUID.randomUUID();
        String reason = "Knee strain recovery";
        FitnessGoal activeGoal = new FitnessGoal(
                goalId, studentId, "Hypertrophy Goal", GoalStatus.ACTIVE, studentId,
                Instant.now(clock).minusSeconds(86400), null, null, null, null,
                Instant.now(clock).minusSeconds(86400), Instant.now(clock).minusSeconds(86400), null
        );

        FitnessGoal pausedGoal = new FitnessGoal(
                goalId, studentId, "Hypertrophy Goal", GoalStatus.PAUSED, studentId,
                activeGoal.activatedAt(), Instant.now(clock), null, null, reason,
                activeGoal.createdAt(), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(activeGoal));
        when(fitnessGoalPersistencePort.pauseGoal(eq(goalId), eq(studentId), eq(reason), any(Instant.class)))
                .thenReturn(pausedGoal);

        FitnessGoal result = fitnessGoalService.pauseFitnessGoal(new PauseFitnessGoalCommand(studentId, goalId, reason));

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(GoalStatus.PAUSED);
        assertThat(result.pausedAt()).isEqualTo(Instant.now(clock));
        assertThat(result.statusReason()).isEqualTo(reason);

        verify(goalStudentAuthorityPort).verifyStudentCanManageGoals(studentId);
        verify(fitnessGoalPersistencePort).pauseGoal(goalId, studentId, reason, Instant.now(clock));
        verify(auditService).recordAudit(argThat((AuditRecord record) ->
                record.actorUserId().equals(studentId)
                        && "STUDENT".equals(record.actorRole())
                        && "FITNESS_GOAL_PAUSED".equals(record.action())
                        && "FITNESS_GOAL".equals(record.targetType())
                        && record.targetId().equals(goalId)
                        && record.metadataJson().contains("PAUSED")
                        && record.metadataJson().contains("Knee strain recovery")
        ));
    }

    @Test
    @DisplayName("Resume goal: success resumes paused goal, clears paused_at, calculates paused duration, and records audit")
    void resumeFitnessGoal_success() {
        UUID goalId = UUID.randomUUID();
        String reason = "Recovery complete";
        Instant pausedAt = Instant.now(clock).minusSeconds(7200); // 2 hours ago
        FitnessGoal pausedGoal = new FitnessGoal(
                goalId, studentId, "Hypertrophy Goal", GoalStatus.PAUSED, studentId,
                Instant.now(clock).minusSeconds(86400), pausedAt, null, null, "Injury",
                Instant.now(clock).minusSeconds(86400), pausedAt, null
        );

        FitnessGoal resumedGoal = new FitnessGoal(
                goalId, studentId, "Hypertrophy Goal", GoalStatus.ACTIVE, studentId,
                pausedGoal.activatedAt(), null, null, null, reason,
                pausedGoal.createdAt(), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(pausedGoal));
        when(fitnessGoalPersistencePort.hasActiveGoal(studentId)).thenReturn(false);
        when(fitnessGoalPersistencePort.resumeGoal(eq(goalId), eq(studentId), eq(reason), any(Instant.class)))
                .thenReturn(resumedGoal);

        FitnessGoal result = fitnessGoalService.resumeFitnessGoal(new ResumeFitnessGoalCommand(studentId, goalId, reason));

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(result.pausedAt()).isNull();
        assertThat(result.statusReason()).isEqualTo(reason);

        verify(goalStudentAuthorityPort).verifyStudentCanManageGoals(studentId);
        verify(fitnessGoalPersistencePort).hasActiveGoal(studentId);
        verify(fitnessGoalPersistencePort).resumeGoal(goalId, studentId, reason, Instant.now(clock));
        verify(auditService).recordAudit(argThat((AuditRecord record) ->
                record.actorUserId().equals(studentId)
                        && "STUDENT".equals(record.actorRole())
                        && "FITNESS_GOAL_RESUMED".equals(record.action())
                        && "FITNESS_GOAL".equals(record.targetType())
                        && record.targetId().equals(goalId)
                        && record.metadataJson().contains("ACTIVE")
                        && record.metadataJson().contains("Recovery complete")
                        && record.metadataJson().contains("\"pausedDurationSeconds\":7200")
        ));
    }

    @Test
    @DisplayName("Pause goal: throws GoalLifecycleConflictException when goal is not ACTIVE")
    void pauseFitnessGoal_whenNotActive_throwsConflict() {
        UUID goalId = UUID.randomUUID();
        FitnessGoal draftGoal = new FitnessGoal(
                goalId, studentId, "Draft Goal", GoalStatus.DRAFT, studentId,
                null, null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(draftGoal));

        assertThatThrownBy(() -> fitnessGoalService.pauseFitnessGoal(new PauseFitnessGoalCommand(studentId, goalId, "Pause draft")))
                .isInstanceOf(GoalLifecycleConflictException.class)
                .hasMessageContaining("Cannot pause fitness goal: goal is not in ACTIVE status");

        verify(fitnessGoalPersistencePort, never()).pauseGoal(any(), any(), any(), any());
        verify(auditService, never()).recordAudit(any());
    }

    @Test
    @DisplayName("Resume goal: throws GoalLifecycleConflictException when goal is not PAUSED")
    void resumeFitnessGoal_whenNotPaused_throwsConflict() {
        UUID goalId = UUID.randomUUID();
        FitnessGoal activeGoal = new FitnessGoal(
                goalId, studentId, "Active Goal", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(activeGoal));

        assertThatThrownBy(() -> fitnessGoalService.resumeFitnessGoal(new ResumeFitnessGoalCommand(studentId, goalId, "Resume active")))
                .isInstanceOf(GoalLifecycleConflictException.class)
                .hasMessageContaining("Cannot resume fitness goal: goal is not in PAUSED status");

        verify(fitnessGoalPersistencePort, never()).resumeGoal(any(), any(), any(), any());
        verify(auditService, never()).recordAudit(any());
    }

    @Test
    @DisplayName("Resume goal: throws ActiveFitnessGoalAlreadyExistsException when another active goal exists")
    void resumeFitnessGoal_whenActiveGoalAlreadyExists_throwsConflict() {
        UUID goalId = UUID.randomUUID();
        FitnessGoal pausedGoal = new FitnessGoal(
                goalId, studentId, "Paused Goal", GoalStatus.PAUSED, studentId,
                Instant.now(clock).minusSeconds(86400), Instant.now(clock).minusSeconds(3600), null, null, null,
                Instant.now(clock).minusSeconds(86400), Instant.now(clock).minusSeconds(3600), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(pausedGoal));
        when(fitnessGoalPersistencePort.hasActiveGoal(studentId)).thenReturn(true);

        assertThatThrownBy(() -> fitnessGoalService.resumeFitnessGoal(new ResumeFitnessGoalCommand(studentId, goalId, "Resume")))
                .isInstanceOf(ActiveFitnessGoalAlreadyExistsException.class)
                .hasMessageContaining("Student already has an active fitness goal");

        verify(fitnessGoalPersistencePort, never()).resumeGoal(any(), any(), any(), any());
        verify(auditService, never()).recordAudit(any());
    }

    @Test
    @DisplayName("Pause goal: throws FitnessGoalAccessDeniedException when student is not the owner")
    void pauseFitnessGoal_whenStudentNotOwner_throwsAccessDenied() {
        UUID goalId = UUID.randomUUID();
        UUID otherStudentId = UUID.randomUUID();
        FitnessGoal otherGoal = new FitnessGoal(
                goalId, otherStudentId, "Other Goal", GoalStatus.ACTIVE, otherStudentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(otherGoal));

        assertThatThrownBy(() -> fitnessGoalService.pauseFitnessGoal(new PauseFitnessGoalCommand(studentId, goalId, "Pause")))
                .isInstanceOf(FitnessGoalAccessDeniedException.class)
                .hasMessageContaining("Access denied to fitness goal");

        verify(fitnessGoalPersistencePort, never()).pauseGoal(any(), any(), any(), any());
        verify(auditService, never()).recordAudit(any());
    }

    @Test
    @DisplayName("Resume goal: throws FitnessGoalAccessDeniedException when student is not the owner")
    void resumeFitnessGoal_whenStudentNotOwner_throwsAccessDenied() {
        UUID goalId = UUID.randomUUID();
        UUID otherStudentId = UUID.randomUUID();
        FitnessGoal otherGoal = new FitnessGoal(
                goalId, otherStudentId, "Other Goal", GoalStatus.PAUSED, otherStudentId,
                Instant.now(clock), Instant.now(clock), null, null, null, Instant.now(clock), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(otherGoal));

        assertThatThrownBy(() -> fitnessGoalService.resumeFitnessGoal(new ResumeFitnessGoalCommand(studentId, goalId, "Resume")))
                .isInstanceOf(FitnessGoalAccessDeniedException.class)
                .hasMessageContaining("Access denied to fitness goal");

        verify(fitnessGoalPersistencePort, never()).resumeGoal(any(), any(), any(), any());
        verify(auditService, never()).recordAudit(any());
    }

    @Test
    @DisplayName("Pause goal: throws FitnessGoalNotFoundException when goal does not exist")
    void pauseFitnessGoal_whenGoalNotFound_throwsNotFound() {
        UUID goalId = UUID.randomUUID();
        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fitnessGoalService.pauseFitnessGoal(new PauseFitnessGoalCommand(studentId, goalId, "Pause")))
                .isInstanceOf(FitnessGoalNotFoundException.class)
                .hasMessageContaining("Fitness goal not found");

        verify(fitnessGoalPersistencePort, never()).pauseGoal(any(), any(), any(), any());
        verify(auditService, never()).recordAudit(any());
    }

    @Test
    @DisplayName("Resume goal: throws FitnessGoalNotFoundException when goal does not exist")
    void resumeFitnessGoal_whenGoalNotFound_throwsNotFound() {
        UUID goalId = UUID.randomUUID();
        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fitnessGoalService.resumeFitnessGoal(new ResumeFitnessGoalCommand(studentId, goalId, "Resume")))
                .isInstanceOf(FitnessGoalNotFoundException.class)
                .hasMessageContaining("Fitness goal not found");

        verify(fitnessGoalPersistencePort, never()).resumeGoal(any(), any(), any(), any());
        verify(auditService, never()).recordAudit(any());
    }

    @Test
    @DisplayName("Pause goal: validates reason blank or exceeding 1000 characters")
    void pauseFitnessGoal_validationErrors() {
        UUID goalId = UUID.randomUUID();

        // Null command
        assertThatThrownBy(() -> fitnessGoalService.pauseFitnessGoal(null))
                .isInstanceOf(ApplicationValidationException.class);

        // Blank reason
        assertThatThrownBy(() -> fitnessGoalService.pauseFitnessGoal(new PauseFitnessGoalCommand(studentId, goalId, "   ")))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Reason is required");

        // Exceeding 1000 chars
        String longReason = "A".repeat(1001);
        assertThatThrownBy(() -> fitnessGoalService.pauseFitnessGoal(new PauseFitnessGoalCommand(studentId, goalId, longReason)))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Reason must not exceed 1000 characters");
    }

    @Test
    @DisplayName("Resume goal: validates reason blank or exceeding 1000 characters")
    void resumeFitnessGoal_validationErrors() {
        UUID goalId = UUID.randomUUID();

        // Null command
        assertThatThrownBy(() -> fitnessGoalService.resumeFitnessGoal(null))
                .isInstanceOf(ApplicationValidationException.class);

        // Blank reason
        assertThatThrownBy(() -> fitnessGoalService.resumeFitnessGoal(new ResumeFitnessGoalCommand(studentId, goalId, "")))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Reason is required");

        // Exceeding 1000 chars
        String longReason = "A".repeat(1001);
        assertThatThrownBy(() -> fitnessGoalService.resumeFitnessGoal(new ResumeFitnessGoalCommand(studentId, goalId, longReason)))
                .isInstanceOf(ApplicationValidationException.class)
                .hasMessageContaining("Reason must not exceed 1000 characters");
    }

    @Test
    @DisplayName("Pause goal: propagates GoalLifecycleConflictException if persistence CAS update fails")
    void pauseFitnessGoal_persistenceConflict_propagatesException() {
        UUID goalId = UUID.randomUUID();
        String reason = "Injury recovery";
        FitnessGoal activeGoal = new FitnessGoal(
                goalId, studentId, "Hypertrophy Goal", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(activeGoal));
        when(fitnessGoalPersistencePort.pauseGoal(eq(goalId), eq(studentId), eq(reason), any(Instant.class)))
                .thenThrow(new GoalLifecycleConflictException("Cannot pause fitness goal: goal status is no longer ACTIVE or was concurrently modified."));

        assertThatThrownBy(() -> fitnessGoalService.pauseFitnessGoal(new PauseFitnessGoalCommand(studentId, goalId, reason)))
                .isInstanceOf(GoalLifecycleConflictException.class)
                .hasMessageContaining("Cannot pause fitness goal: goal status is no longer ACTIVE");

        verify(auditService, never()).recordAudit(any());
    }

    @Test
    @DisplayName("Resume goal: propagates GoalLifecycleConflictException if persistence CAS update fails")
    void resumeFitnessGoal_persistenceConflict_propagatesException() {
        UUID goalId = UUID.randomUUID();
        String reason = "Cleared to train";
        FitnessGoal pausedGoal = new FitnessGoal(
                goalId, studentId, "Hypertrophy Goal", GoalStatus.PAUSED, studentId,
                Instant.now(clock), Instant.now(clock), null, null, null, Instant.now(clock), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(pausedGoal));
        when(fitnessGoalPersistencePort.hasActiveGoal(studentId)).thenReturn(false);
        when(fitnessGoalPersistencePort.resumeGoal(eq(goalId), eq(studentId), eq(reason), any(Instant.class)))
                .thenThrow(new GoalLifecycleConflictException("Cannot resume fitness goal: goal status is no longer PAUSED or was concurrently modified."));

        assertThatThrownBy(() -> fitnessGoalService.resumeFitnessGoal(new ResumeFitnessGoalCommand(studentId, goalId, reason)))
                .isInstanceOf(GoalLifecycleConflictException.class)
                .hasMessageContaining("Cannot resume fitness goal: goal status is no longer PAUSED");

        verify(auditService, never()).recordAudit(any());
    }

    @Test
    @DisplayName("Pause goal: throws exception and triggers rollback when audit logging fails")
    void pauseFitnessGoal_auditFailure_throwsException() {
        UUID goalId = UUID.randomUUID();
        String reason = "Injury recovery";
        FitnessGoal activeGoal = new FitnessGoal(
                goalId, studentId, "Hypertrophy Goal", GoalStatus.ACTIVE, studentId,
                Instant.now(clock), null, null, null, null, Instant.now(clock), Instant.now(clock), null
        );
        FitnessGoal pausedGoal = new FitnessGoal(
                goalId, studentId, "Hypertrophy Goal", GoalStatus.PAUSED, studentId,
                activeGoal.activatedAt(), Instant.now(clock), null, null, reason,
                activeGoal.createdAt(), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(activeGoal));
        when(fitnessGoalPersistencePort.pauseGoal(eq(goalId), eq(studentId), eq(reason), any(Instant.class)))
                .thenReturn(pausedGoal);
        org.mockito.Mockito.doThrow(new RuntimeException("Audit persistence failed"))
                .when(auditService).recordAudit(any());

        assertThatThrownBy(() -> fitnessGoalService.pauseFitnessGoal(new PauseFitnessGoalCommand(studentId, goalId, reason)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Audit persistence failed");
    }

    @Test
    @DisplayName("Resume goal: throws exception and triggers rollback when audit logging fails")
    void resumeFitnessGoal_auditFailure_throwsException() {
        UUID goalId = UUID.randomUUID();
        String reason = "Recovery complete";
        FitnessGoal pausedGoal = new FitnessGoal(
                goalId, studentId, "Hypertrophy Goal", GoalStatus.PAUSED, studentId,
                Instant.now(clock), Instant.now(clock), null, null, null, Instant.now(clock), Instant.now(clock), null
        );
        FitnessGoal resumedGoal = new FitnessGoal(
                goalId, studentId, "Hypertrophy Goal", GoalStatus.ACTIVE, studentId,
                pausedGoal.activatedAt(), null, null, null, reason,
                pausedGoal.createdAt(), Instant.now(clock), null
        );

        when(fitnessGoalPersistencePort.findById(goalId)).thenReturn(Optional.of(pausedGoal));
        when(fitnessGoalPersistencePort.hasActiveGoal(studentId)).thenReturn(false);
        when(fitnessGoalPersistencePort.resumeGoal(eq(goalId), eq(studentId), eq(reason), any(Instant.class)))
                .thenReturn(resumedGoal);
        org.mockito.Mockito.doThrow(new RuntimeException("Audit persistence failed"))
                .when(auditService).recordAudit(any());

        assertThatThrownBy(() -> fitnessGoalService.resumeFitnessGoal(new ResumeFitnessGoalCommand(studentId, goalId, reason)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Audit persistence failed");
    }

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher) {
        return org.mockito.Mockito.argThat(matcher);
    }
}
