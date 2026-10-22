package com.fitnesscoaching.platform.modules.workout.application.port.in;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface WorkoutRecurrenceUseCase {
    record Item(UUID planSessionId, int weekNumber, int dayNumber, int sequenceNumber,
                LocalDateTime firstLocalStart, LocalDateTime firstLocalEnd) {}
    record Create(UUID actorId, UUID studentId, UUID planId, UUID sourcePlanVersionId,
                  long expectedPlanAggregateVersion, LocalDate mondayAnchorDate,
                  String timezone, List<Item> items, String commandKey) {}
    record Series(UUID seriesId, UUID studentId, UUID planId, UUID sourcePlanVersionId,
                  int sourcePlanVersionNumber, long observedPlanAggregateVersion, int planWeeks,
                  LocalDate mondayAnchorDate, String timezone, String status, long seriesVersion,
                  UUID initiatedBy, UUID proposingTrainerId, UUID coachingPeriodId, UUID confirmedBy,
                  Instant createdAt, Instant confirmedAt, Instant stoppedAt, List<Item> items,
                  boolean replayed) {
        public Series asReplay() { return new Series(seriesId, studentId, planId, sourcePlanVersionId,
                sourcePlanVersionNumber, observedPlanAggregateVersion, planWeeks, mondayAnchorDate,
                timezone, status, seriesVersion, initiatedBy, proposingTrainerId, coachingPeriodId,
                confirmedBy, createdAt, confirmedAt, stoppedAt, items, true); }
    }
    record Page<T>(List<T> items, int page, int size, long totalItems, int totalPages) {}
    record Decision(UUID actorId, UUID seriesId, long expectedSeriesVersion,
                    String decision, String commandKey) {}
    record VersionCommand(UUID actorId, UUID seriesId, long expectedSeriesVersion, String commandKey) {}
    record Period(UUID periodId, UUID seriesId, long blockIteration, UUID planSessionId,
                  LocalDateTime localStart, LocalDateTime localEnd, String timezone,
                  String startUtcOffset, String endUtcOffset, Instant plannedStartAt,
                  Instant plannedEndAt, String outcome, UUID occurrenceId, Instant processedAt) {}

    Series confirmStudent(Create command);
    Series proposeTrainer(Create command);
    Page<Series> listStudent(UUID actorId, String status, int page, int size);
    Page<Series> listTrainer(UUID actorId, UUID studentId, String status, int page, int size);
    Series detail(UUID actorId, UUID seriesId);
    Page<Period> periods(UUID actorId, UUID seriesId, int page, int size);
    Series decide(Decision command);
    Series stop(VersionCommand command);
    Series withdraw(VersionCommand command);
    void materialize(UUID seriesId);
    List<UUID> activeSeriesIds();
}
