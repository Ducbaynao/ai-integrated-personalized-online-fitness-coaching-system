package com.fitnesscoaching.platform.modules.notification;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.AppointmentReminderQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutReminderQuery;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** PostgreSQL-backed, replay-safe scheduler. Source reads stay behind owner ports. */
@Service
public class TimedReminderService {
    private final JdbcTemplate jdbc;
    private final AppointmentReminderQuery appointments;
    private final WorkoutReminderQuery workouts;
    private final CurrentCoachingContextQuery contexts;
    private final InAppNotificationService notices;
    private final ReminderPreferenceService preferences;
    private final PushQueueWriter push;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;

    public TimedReminderService(JdbcTemplate jdbc, AppointmentReminderQuery appointments,
            WorkoutReminderQuery workouts, CurrentCoachingContextQuery contexts,
            InAppNotificationService notices, ReminderPreferenceService preferences,
            PushQueueWriter push, ApplicationEventPublisher events,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.appointments = appointments; this.workouts = workouts;
        this.contexts = contexts; this.notices = notices; this.preferences = preferences;
        this.push = push; this.events = events;
        this.transactions = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${notifications.reminders.worker-delay-ms:30000}")
    public void dispatchDue() {
        Instant now = databaseNow();
        Instant through = now.plus(60, ChronoUnit.MINUTES);
        for (var slot : appointments.startingBetween(now, through)) {
            dispatch("APPOINTMENT", slot.id(), slot.version(), slot.student(), slot.trainer(),
                    slot.period(), slot.startAt(), slot.student());
            dispatch("APPOINTMENT", slot.id(), slot.version(), slot.student(), slot.trainer(),
                    slot.period(), slot.startAt(), slot.trainer());
        }
        for (var slot : workouts.startingBetween(now, through)) {
            dispatch("PLANNED_WORKOUT", slot.id(), slot.version(), slot.student(), null,
                    null, slot.startAt(), slot.student());
        }
    }

    private void dispatch(String sourceType, UUID sourceId, long version, UUID student,
            UUID trainer, UUID period, Instant start, UUID recipient) {
        transactions.executeWithoutResult(ignored -> {
            for (String channel : List.of("IN_APP", "PUSH"))
                emit(sourceType, sourceId, version, student, trainer, period, start, recipient, channel);
        });
    }

    private void emit(String sourceType, UUID sourceId, long version, UUID student,
            UUID trainer, UUID period, Instant start, UUID recipient, String channel) {
        String category = sourceType.equals("APPOINTMENT") ? "APPOINTMENT_REMINDER" : "WORKOUT_REMINDER";
        Instant now = databaseNow();
        Instant due = start.minus(60, ChronoUnit.MINUTES);
        if (now.isBefore(due) || !now.isBefore(start)) return;
        // Acquire source share lock before the reminder row, matching owner-writer order.
        if (!sourceCurrent(sourceType, sourceId, version, start)) return;
        boolean authorized = recipient != null && notices.canReadReminder(recipient, student, trainer,
                period, sourceType, sourceId) && (period == null || activePeriod(student, trainer, period));
        boolean optedIn = channel.equals("IN_APP") ? preferences.enabled(recipient, category)
                : pushEligible(recipient, category);
        List<UUID> inserted = jdbc.query("""
                INSERT INTO fitness.timed_reminders
                  (source_type,source_id,source_version,recipient_user_id,channel,
                   remind_at,source_start_at,status)
                VALUES (?,?,?,?,?::fitness.notification_channel,?,?,'SKIPPED')
                ON CONFLICT DO NOTHING RETURNING id
                """, (rs, n) -> rs.getObject(1, UUID.class), sourceType, sourceId, version,
                recipient, channel, Timestamp.from(due), Timestamp.from(start));
        if (inserted.isEmpty() || !authorized || !optedIn) return;
        UUID row = inserted.getFirst();
        UUID notification = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.notifications
                  (id,recipient_user_id,event_type,title,body,related_entity_type,related_entity_id,
                   coaching_period_id,source_event_id,source_student_id,source_trainer_id,inbox_visible)
                VALUES (?,?,?, 'Lịch sắp bắt đầu',
                  'Bạn có một lịch sắp bắt đầu',?,?,?,?,?,?,?)
                """, notification, recipient, category, sourceType, sourceId, period, row, student, trainer,
                channel.equals("IN_APP"));
        if (channel.equals("IN_APP")) {
            jdbc.update("UPDATE fitness.timed_reminders SET status='EMITTED',notification_id=? WHERE id=?",
                    notification, row);
            jdbc.update("""
                    INSERT INTO fitness.notification_deliveries(notification_id,channel,status)
                    VALUES (?,'IN_APP','DELIVERED')
                    """, notification);
            events.publishEvent(new LifecycleNotificationWriter.Changed(recipient, notification));
        } else if (push.queue(notification, recipient, category) > 0) {
            jdbc.update("UPDATE fitness.timed_reminders SET status='EMITTED',notification_id=? WHERE id=?",
                    notification, row);
        } else jdbc.update("DELETE FROM fitness.notifications WHERE id=?", notification);
    }

    private boolean pushEligible(UUID recipient, String category) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.notification_preferences p
                  JOIN fitness.push_devices d ON d.user_id=p.user_id AND d.active=true
                  WHERE p.user_id=? AND p.event_type=? AND p.channel='PUSH' AND p.enabled=true
                    AND p.timezone IS NOT NULL)
                """, Boolean.class, recipient, category));
    }

    private boolean activePeriod(UUID student, UUID trainer, UUID period) {
        return contexts.findEffectiveContext(student, false)
                .filter(context -> context.mode() == CurrentCoachingContextQuery.Mode.HUMAN_COACH
                        && period.equals(context.periodId()) && trainer.equals(context.trainerId()))
                .isPresent();
    }

    private boolean sourceCurrent(String type, UUID id, long version, Instant start) {
        if (type.equals("APPOINTMENT")) return appointments.confirmed(id)
                .filter(value -> value.version() == version && value.startAt().equals(start)).isPresent();
        return workouts.scheduled(id)
                .filter(value -> value.version() == version && value.startAt().equals(start)).isPresent();
    }

    /** Called by the push worker immediately before provider I/O. */
    public boolean currentForPush(UUID notificationId, UUID recipient, Instant at) {
        List<ReminderRow> rows = jdbc.query("""
                SELECT source_type,source_id,source_version,source_start_at
                FROM fitness.timed_reminders WHERE notification_id=? AND recipient_user_id=?
                """, (rs, n) -> new ReminderRow(rs.getString(1), rs.getObject(2, UUID.class),
                rs.getLong(3), rs.getTimestamp(4).toInstant()), notificationId, recipient);
        if (rows.isEmpty()) return true; // Non-reminder push uses existing policy.
        ReminderRow row = rows.getFirst();
        if (!at.isBefore(row.start()) || !sourceCurrent(row.type(), row.id(), row.version(), row.start()))
            return false;
        if (row.type().equals("APPOINTMENT")) {
            var slot = appointments.confirmed(row.id()).orElseThrow();
            return activePeriod(slot.student(), slot.trainer(), slot.period())
                    && notices.canReadReminder(recipient, slot.student(), slot.trainer(), slot.period(),
                    row.type(), row.id());
        }
        var slot = workouts.scheduled(row.id()).orElseThrow();
        return notices.canReadReminder(recipient, slot.student(), null, null, row.type(), row.id());
    }

    public boolean canWaitForQuietHours(UUID notificationId, UUID recipient, Instant nextAllowed) {
        return jdbc.query("""
                SELECT source_start_at FROM fitness.timed_reminders
                WHERE notification_id=? AND recipient_user_id=?
                """, (rs, n) -> nextAllowed.isBefore(rs.getTimestamp(1).toInstant()),
                notificationId, recipient).stream().findFirst().orElse(true);
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", (rs, n) -> rs.getTimestamp(1).toInstant());
    }

    private record ReminderRow(String type, UUID id, long version, Instant start) {}
}
