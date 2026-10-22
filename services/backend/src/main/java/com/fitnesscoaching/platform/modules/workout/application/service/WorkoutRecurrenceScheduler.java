package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutRecurrenceUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WorkoutRecurrenceScheduler {
    private static final Logger log = LoggerFactory.getLogger(WorkoutRecurrenceScheduler.class);
    private final WorkoutRecurrenceUseCase recurrence;
    public WorkoutRecurrenceScheduler(WorkoutRecurrenceUseCase recurrence) { this.recurrence = recurrence; }

    @Scheduled(cron = "${app.scheduling.workout-recurrence.cron:0 0 2 * * *}", zone = "UTC")
    public void runDaily() {
        RuntimeException firstFailure = null;
        for (var seriesId : recurrence.activeSeriesIds()) {
            try { recurrence.materialize(seriesId); }
            catch (RuntimeException failure) {
                log.error("Workout recurrence materialization failed for series {}", seriesId, failure);
                if (firstFailure == null) firstFailure = failure;
            }
        }
        if (firstFailure != null) throw firstFailure;
    }
}
