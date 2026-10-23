package com.fitnesscoaching.platform.modules.notification;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.chat.ChatFailure;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingNotificationAccessQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerCoachingEligibilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class InAppNotificationService implements ChatNotificationWriter, LifecycleNotificationWriter {
    private final JdbcTemplate jdbc;
    private final CurrentCoachingContextQuery contexts;
    private final UserAccountStatusQuery accounts;
    private final TrainerCoachingEligibilityQuery trainers;
    private final CoachingAuthorityQuery authority;
    private final CoachingNotificationAccessQuery coachingNotifications;
    private final ApplicationEventPublisher events;

    public InAppNotificationService(JdbcTemplate jdbc, CurrentCoachingContextQuery contexts,
                                    UserAccountStatusQuery accounts, TrainerCoachingEligibilityQuery trainers,
                                    CoachingAuthorityQuery authority,
                                    CoachingNotificationAccessQuery coachingNotifications,
                                    ApplicationEventPublisher events) {
        this.jdbc = jdbc; this.contexts = contexts; this.accounts = accounts; this.trainers = trainers;
        this.authority = authority;
        this.coachingNotifications = coachingNotifications;
        this.events = events;
    }

    public record Item(UUID id, String eventType, String title, String body, UUID messageId,
                       String relatedEntityType, UUID relatedEntityId,
                       UUID coachingPeriodId, Instant createdAt, Instant readAt) {}
    public record Page(List<Item> items, int page, int size, boolean hasMore) {}

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void committed(UUID sourceEventId, String type, String entityType, UUID entityId,
                          UUID student, UUID trainer, UUID period, UUID actor, UUID recipient) {
        if (sourceEventId == null || type == null || entityType == null || entityId == null
                || !List.of("APPOINTMENT", "APPOINTMENT_REQUEST", "WORKOUT_SCHEDULE_PROPOSAL",
                    "WORKOUT_CHANGE_PROPOSAL", "NUTRITION_STRATEGY_PROPOSAL", "FITNESS_GOAL_PROPOSAL",
                     "COACHING_REQUEST", "COACHING_RESUME_REQUEST", "PLANNED_WORKOUT").contains(entityType)
                || recipient == null || recipient.equals(actor)
                || !visible(recipient, student, trainer, period, entityType, entityId)
                || accounts.getAccountStatus(recipient).orElse(null) != AccountStatus.ACTIVE) return;
        UUID id = UUID.randomUUID();
        List<UUID> inserted = jdbc.query("""
                INSERT INTO fitness.notifications
                (id,recipient_user_id,event_type,title,body,related_entity_type,related_entity_id,
                 coaching_period_id,source_event_id,source_student_id,source_trainer_id)
                VALUES (?,?,?,'Thông báo mới','Bạn có cập nhật mới',?,?,?,?,?,?)
                ON CONFLICT DO NOTHING RETURNING id
                """, (rs, row) -> rs.getObject(1, UUID.class), id, recipient, type,
                entityType, entityId, period, sourceEventId, student, trainer);
        if (!inserted.isEmpty()) {
            jdbc.update("""
                    INSERT INTO fitness.notification_deliveries(notification_id,channel,status)
                    VALUES (?,'IN_APP','DELIVERED')
                    """, id);
            events.publishEvent(new LifecycleNotificationWriter.Changed(recipient, id));
        }
    }

    /** Used only for a post-commit socket invalidation; REST remains authoritative. */
    @Transactional
    public boolean canReadNotice(UUID actor, UUID id) {
        if (actor == null || id == null || accounts.getAccountStatus(actor).orElse(null) != AccountStatus.ACTIVE)
            return false;
        return jdbc.query("""
                SELECT COALESCE(chat_student_id,source_student_id),
                       COALESCE(chat_trainer_id,source_trainer_id),coaching_period_id,
                       related_entity_type,related_entity_id
                FROM fitness.notifications WHERE id=? AND recipient_user_id=?
                """, (rs, row) -> visible(actor, rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getString(4), rs.getObject(5, UUID.class)), id, actor)
                .stream().findFirst().orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<UUID> sourcePeriod(String entityType, UUID entityId) {
        if (entityType == null || entityId == null) return java.util.Optional.empty();
        return jdbc.query("""
                SELECT coaching_period_id FROM fitness.notifications
                WHERE related_entity_type=? AND related_entity_id=?
                  AND event_type=? AND source_event_id IS NOT NULL
                ORDER BY created_at ASC,id ASC LIMIT 1
                """, (rs, row) -> rs.getObject(1, UUID.class), entityType, entityId,
                entityType + "_PENDING").stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID messageCommitted(UUID recipient, UUID message, UUID period, UUID student, UUID trainer) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.notifications(id,recipient_user_id,event_type,title,body,
                  related_entity_type,related_entity_id,coaching_period_id,chat_student_id,chat_trainer_id)
                VALUES (?,?,'CHAT_MESSAGE','Tin nhắn mới','Bạn có tin nhắn mới','MESSAGE',?,?,?,?)
                """, id, recipient, message, period, student, trainer);
        jdbc.update("""
                INSERT INTO fitness.notification_deliveries(notification_id,channel,status)
                VALUES (?,'IN_APP','DELIVERED')
                """, id);
        return id;
    }

    // Current Coaching authority locks the grant row, so this transaction must not be read-only.
    @Transactional
    public Page list(UUID actor, int page, int size) {
        validatePage(page, size);
        requireAccount(actor);
        List<Item> candidates = jdbc.query("""
                SELECT id,event_type,title,body,related_entity_type,related_entity_id,coaching_period_id,
                       COALESCE(chat_student_id,source_student_id) AS source_student_id,
                       COALESCE(chat_trainer_id,source_trainer_id) AS source_trainer_id,
                       created_at,read_at
                FROM fitness.notifications WHERE recipient_user_id=?
                  AND (event_type='CHAT_MESSAGE' OR related_entity_type IN ('APPOINTMENT','APPOINTMENT_REQUEST',
                      'WORKOUT_SCHEDULE_PROPOSAL','WORKOUT_CHANGE_PROPOSAL','NUTRITION_STRATEGY_PROPOSAL',
                       'FITNESS_GOAL_PROPOSAL','COACHING_REQUEST','COACHING_RESUME_REQUEST',
                       'PLANNED_WORKOUT'))
                ORDER BY created_at DESC,id DESC
                """, (rs, row) -> visible(actor, rs.getObject("source_student_id", UUID.class),
                rs.getObject("source_trainer_id", UUID.class), rs.getObject("coaching_period_id", UUID.class),
                rs.getString("related_entity_type"), rs.getObject("related_entity_id", UUID.class))
                ? new Item(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("title"),
                    rs.getString("body"), "MESSAGE".equals(rs.getString("related_entity_type"))
                            ? rs.getObject("related_entity_id", UUID.class) : null,
                    rs.getString("related_entity_type"), rs.getObject("related_entity_id", UUID.class),
                    rs.getObject("coaching_period_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("read_at") == null ? null : rs.getTimestamp("read_at").toInstant())
                : null, actor).stream().filter(item -> item != null).toList();
        int from = (int) Math.min((long) page * size, candidates.size());
        int until = Math.min(from + size, candidates.size());
        return new Page(candidates.subList(from, until), page, size, until < candidates.size());
    }

    @Transactional
    public Item markRead(UUID actor, UUID id) {
        requireAccount(actor);
        List<NotificationRow> rows = jdbc.query("""
                SELECT COALESCE(chat_student_id,source_student_id),
                       COALESCE(chat_trainer_id,source_trainer_id),coaching_period_id,related_entity_type,
                       related_entity_id
                FROM fitness.notifications
                WHERE id=? AND recipient_user_id=? FOR UPDATE
                """, (rs, row) -> new NotificationRow(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                rs.getObject(5, UUID.class)), id, actor);
        if (rows.isEmpty() || !visible(actor, rows.getFirst().student(), rows.getFirst().trainer(),
                rows.getFirst().period(), rows.getFirst().entityType(), rows.getFirst().entityId())) {
            throw new ChatFailure("CHAT_NOTIFICATION_NOT_FOUND", 404);
        }
        jdbc.update("UPDATE fitness.notifications SET read_at=COALESCE(read_at,clock_timestamp()) WHERE id=?", id);
        return jdbc.queryForObject("""
                SELECT id,event_type,title,body,related_entity_type,related_entity_id,
                       coaching_period_id,created_at,read_at
                FROM fitness.notifications WHERE id=?
                """, (rs, row) -> new Item(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), "MESSAGE".equals(rs.getString(5)) ? rs.getObject(6, UUID.class) : null,
                rs.getString(5), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class),
                rs.getTimestamp(8).toInstant(), rs.getTimestamp(9).toInstant()), id);
    }

    private boolean visible(UUID actor, UUID student, UUID trainer, UUID period, String entityType, UUID entityId) {
        if ("COACHING_REQUEST".equals(entityType) || "COACHING_RESUME_REQUEST".equals(entityType))
            return (actor.equals(student) || actor.equals(trainer))
                    && (actor.equals(student) || trainers.canCoach(actor))
                    && coachingNotifications.canRead(actor, entityType, entityId);
        if (actor.equals(student)) return true;
        if (!actor.equals(trainer) || period == null || !trainers.canCoach(actor)) return false;
        boolean currentPeriod = contexts.findEffectiveContext(student, false)
                .filter(context -> context.mode() == CurrentCoachingContextQuery.Mode.HUMAN_COACH
                        && period.equals(context.periodId()) && actor.equals(context.trainerId()))
                .isPresent();
        if (!currentPeriod) return false;
        DataScope scope = switch (entityType) {
            case "WORKOUT_SCHEDULE_PROPOSAL", "WORKOUT_CHANGE_PROPOSAL", "PLANNED_WORKOUT" -> DataScope.WORKOUT_PLAN;
            case "NUTRITION_STRATEGY_PROPOSAL" -> DataScope.NUTRITION_GOAL;
            case "FITNESS_GOAL_PROPOSAL" -> DataScope.FITNESS_GOAL;
            default -> null;
        };
        return scope == null || authority.evaluateCurrent(new AuthorityRequest(actor, student,
                scope, DataAccessLevel.VIEW)).allowed();
    }

    private void requireAccount(UUID actor) {
        if (accounts.getAccountStatus(actor).orElse(null) != AccountStatus.ACTIVE)
            throw new ChatFailure("CHAT_NOTIFICATION_NOT_FOUND", 404);
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 50 || (long) page * size > Integer.MAX_VALUE)
            throw new ChatFailure("VALIDATION_FAILED", 400);
    }
    private record NotificationRow(UUID student, UUID trainer, UUID period, String entityType, UUID entityId) {}
}
